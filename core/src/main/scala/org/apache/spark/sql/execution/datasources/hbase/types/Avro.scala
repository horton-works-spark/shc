/*
 * Copyright 2014 Databricks
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.hbase.types

import java.io.{IOException, ByteArrayInputStream}
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.util
import java.util.HashMap

import scala.collection.immutable.Map
import scala.collection.JavaConversions._

import org.apache.avro.io._
import org.apache.avro.{SchemaBuilder, Schema}
import org.apache.avro.Schema.Type._
import org.apache.avro.SchemaBuilder._
import org.apache.avro.generic.GenericData.{Record, Fixed}
import org.apache.avro.generic.{GenericDatumReader, GenericDatumWriter, GenericData, GenericRecord}
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.sql.execution.datasources.hbase._

class Avro(f:Option[Field] = None) extends SHCDataType {

  def fromBytes(src: HBaseType): Any = {
    if (f.isDefined) {
      val m = AvroSerde.deserialize(src, f.get.exeSchema.get)
      val n = f.get.avroToCatalyst.map(_ (m))
      n.get
    } else {
      throw new UnsupportedOperationException(
        "Avro coder: without field metadata, 'fromBytes' conversion can not be supported")
    }
  }

  def toBytes(input: Any): Array[Byte] = {
    // Here we assume the top level type is structType
    if (f.isDefined) {
      val record = f.get.catalystToAvro(input)
      AvroSerde.serialize(record, f.get.exeSchema.get) 
    } else {
      throw new UnsupportedOperationException(
        "Avro coder: Without field metadata, 'toBytes' conversion can not be supported")
    }
  }
}

abstract class AvroException(msg: String) extends Exception(msg)
class SchemaConversionException(msg: String) extends AvroException(msg)

object SchemaConverters {

  case class SchemaType(dataType: DataType, nullable: Boolean)

  private def getSchemaBuilder(isNullable: Boolean): BaseTypeBuilder[Schema] = {
    if (isNullable) {
      SchemaBuilder.builder().nullable()
    } else {
      SchemaBuilder.builder()
    }
  }

  // This function takes an avro schema and returns a sql schema.
  def toSqlType(avroSchema: Schema): SchemaType = {
    avroSchema.getType match {
      case INT => SchemaType(IntegerType, nullable = false)
      case STRING => SchemaType(StringType, nullable = false)
      case BOOLEAN => SchemaType(BooleanType, nullable = false)
      case BYTES => SchemaType(BinaryType, nullable = false)
      case DOUBLE => SchemaType(DoubleType, nullable = false)
      case FLOAT => SchemaType(FloatType, nullable = false)
      case LONG => SchemaType(LongType, nullable = false)
      case FIXED => SchemaType(BinaryType, nullable = false)
      case ENUM => SchemaType(StringType, nullable = false)

      case RECORD =>
        val fields = avroSchema.getFields.map { f =>
          val schemaType = toSqlType(f.schema())
          StructField(f.name, schemaType.dataType, schemaType.nullable)
        }

        SchemaType(StructType(fields), nullable = false)

      case ARRAY =>
        val schemaType = toSqlType(avroSchema.getElementType)
        SchemaType(
          ArrayType(schemaType.dataType, containsNull = schemaType.nullable),
          nullable = false)

      case MAP =>
        val schemaType = toSqlType(avroSchema.getValueType)
        SchemaType(
          MapType(StringType, schemaType.dataType, valueContainsNull = schemaType.nullable),
          nullable = false)

      case UNION =>
        if (avroSchema.getTypes.exists(_.getType == NULL)) {
          // In case of a union with null, eliminate it and make a recursive call
          val remainingUnionTypes = avroSchema.getTypes.filterNot(_.getType == NULL)
          if (remainingUnionTypes.size == 1) {
            toSqlType(remainingUnionTypes.get(0)).copy(nullable = true)
          } else {
            toSqlType(Schema.createUnion(remainingUnionTypes)).copy(nullable = true)
          }
        } else avroSchema.getTypes.map(_.getType) match {
          case Seq(t1, t2) if Set(t1, t2) == Set(INT, LONG) =>
            SchemaType(LongType, nullable = false)
          case Seq(t1, t2) if Set(t1, t2) == Set(FLOAT, DOUBLE) =>
            SchemaType(DoubleType, nullable = false)
          case other => throw new SchemaConversionException(
            s"This mix of union types is not supported (see README): $other")
        }

      case other => throw new SchemaConversionException(s"Unsupported type $other")
    }
  }

  // This function is used to convert some sparkSQL type to avro type. Note that this function won't
  // be used to construct fields of avro record (convertFieldTypeToAvro is used for that).
  private def convertSparkSQLTypeToAvro[T](
      dataType: DataType,
      schemaBuilder: BaseTypeBuilder[T],
      structName: String,
      recordNamespace: String): T = {

    dataType match {
      case ByteType => schemaBuilder.intType()
      case ShortType => schemaBuilder.intType()
      case IntegerType => schemaBuilder.intType()
      case LongType => schemaBuilder.longType()
      case FloatType => schemaBuilder.floatType()
      case DoubleType => schemaBuilder.doubleType()
      case _: DecimalType => schemaBuilder.stringType()
      case StringType => schemaBuilder.stringType()
      case BinaryType => schemaBuilder.bytesType()
      case BooleanType => schemaBuilder.booleanType()
      case TimestampType => schemaBuilder.longType()

      case ArrayType(elementType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[ArrayType].containsNull)
        val elementSchema = convertSparkSQLTypeToAvro(elementType, builder, structName, recordNamespace)
        schemaBuilder.array().items(elementSchema)

      case MapType(StringType, valueType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[MapType].valueContainsNull)
        val valueSchema = convertSparkSQLTypeToAvro(valueType, builder, structName, recordNamespace)
        schemaBuilder.map().values(valueSchema)

      case structType: StructType =>
        convertSparkStructTypeToAvro(
          structType,
          schemaBuilder.record(structName).namespace(recordNamespace),
          recordNamespace)

      case other => throw new IllegalArgumentException(s"Unexpected type $dataType.")
    }
  }

  // This function is used to construct fields of the avro record, where schema of the field is
  // specified by avro representation of dataType. Since builders for record fields are different
  // from those for everything else, we have to use a separate method.
  private def convertFieldTypeToAvro[T](
      dataType: DataType,
      newFieldBuilder: BaseFieldTypeBuilder[T],
      structName: String,
      recordNamespace: String): FieldDefault[T, _] = {

    dataType match {
      case ByteType => newFieldBuilder.intType()
      case ShortType => newFieldBuilder.intType()
      case IntegerType => newFieldBuilder.intType()
      case LongType => newFieldBuilder.longType()
      case FloatType => newFieldBuilder.floatType()
      case DoubleType => newFieldBuilder.doubleType()
      case _: DecimalType => newFieldBuilder.stringType()
      case StringType => newFieldBuilder.stringType()
      case BinaryType => newFieldBuilder.bytesType()
      case BooleanType => newFieldBuilder.booleanType()
      case TimestampType => newFieldBuilder.longType()

      case ArrayType(elementType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[ArrayType].containsNull)
        val elementSchema = convertSparkSQLTypeToAvro(elementType, builder, structName, recordNamespace)
        newFieldBuilder.array().items(elementSchema)

      case MapType(StringType, valueType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[MapType].valueContainsNull)
        val valueSchema = convertSparkSQLTypeToAvro(valueType, builder, structName, recordNamespace)
        newFieldBuilder.map().values(valueSchema)

      case structType: StructType =>
        convertSparkStructTypeToAvro(
          structType,
          newFieldBuilder.record(structName).namespace(recordNamespace),
          recordNamespace)

      case other => throw new IllegalArgumentException(s"Unexpected type $dataType.")
    }
  }

  // This function converts sparkSQL StructType into avro schema. This method uses two other
  // converter methods in order to do the conversion.
  private def convertSparkStructTypeToAvro[T](
      structType: StructType,
      schemaBuilder: RecordBuilder[T],
      recordNamespace: String): T = {

    val fieldsAssembler: FieldAssembler[T] = schemaBuilder.fields()
    structType.fields.foreach { field =>
      val newField = fieldsAssembler.name(field.name).`type`()

      if (field.nullable) {
        convertFieldTypeToAvro(field.dataType, newField.nullable(), field.name, recordNamespace)
          .noDefault
      } else {
        convertFieldTypeToAvro(field.dataType, newField, field.name, recordNamespace)
          .noDefault
      }
    }
    fieldsAssembler.endRecord()
  }

  // This function constructs converter function for a given sparkSQL datatype. This is used in
  // writing Avro records out to disk.
  def createConverterToAvro(
      dataType: DataType,
      avroType: Schema,
      currentFieldName: String,
      recordNamespace: String): (Any) => Any = {

    dataType match {
      case BinaryType => (item: Any) => item match {
        case null => null
        case bytes: Array[Byte] => ByteBuffer.wrap(bytes)
      }
      case ByteType | ShortType | IntegerType | LongType |
           FloatType | DoubleType | StringType | BooleanType => identity
      case _: DecimalType => (item: Any) => if (item == null) null else item.toString
      case TimestampType => (item: Any) =>
        if (item == null) null else item.asInstanceOf[Timestamp].getTime
      case ArrayType(elementType, _) =>
        val elementConverter = createConverterToAvro(
          elementType,
          avroType.getElementType, 
          avroType.getElementType.getName, 
          recordNamespace)
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val sourceArray = item.asInstanceOf[Seq[Any]]
            val sourceArraySize = sourceArray.size
            val targetArray = new util.ArrayList[Any](sourceArraySize)
            var idx = 0
            while (idx < sourceArraySize) {
              targetArray.add(elementConverter(sourceArray(idx)))
              idx += 1
            }
            targetArray
          }
        }
      case MapType(StringType, valueType, _) =>
        val valueConverter = createConverterToAvro(
          valueType,
          avroType.getValueType,
          avroType.getValueType.getName, 
          recordNamespace)
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val javaMap = new HashMap[String, Any]()
            item.asInstanceOf[Map[String, Any]].foreach { case (key, value) =>
              javaMap.put(key, valueConverter(value))
            }
            javaMap
          }
        }
      case structType: StructType =>
        // Avro schema is the user supplied one, not the one generated from the dataset
        val schema: Schema = avroType
        // Build in the dataset order
        val fieldConverters = structType.fields.map(field =>
          createConverterToAvro(
            field.dataType,
            schema.getField(field.name).schema(),
            field.name,
            recordNamespace))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val record = new Record(schema)
            val convertersIterator = fieldConverters.iterator
            val fieldNamesIterator = dataType.asInstanceOf[StructType].fieldNames.iterator
            val row = item.asInstanceOf[Row]
            
            // Resolve the column by name, don't use the iterator row.toSeq.iterator
            // which doesn't deliver columns in the expected order
            while (fieldNamesIterator.hasNext) {
              val fieldname = fieldNamesIterator.next()
              val converter = convertersIterator.next()
              record.put(fieldname, converter(row.get(row.fieldIndex(fieldname))))
            }
            record
          }
        }
    }
  }

  // Returns a function that is used to convert avro types to their corresponding sparkSQL representations.
  def createConverterToSQL(schema: Schema): Any => Any = {
    schema.getType match {
      // Avro strings are in Utf8, so we have to call toString on them
      case STRING | ENUM => (item: Any) => if (item == null) null else item.toString
      case INT | BOOLEAN | DOUBLE | FLOAT | LONG => identity
      // Byte arrays are reused by avro, so we have to make a copy of them.
      case FIXED => (item: Any) => if (item == null) {
        null
      } else {
        item.asInstanceOf[Fixed].bytes().clone()
      }
      case BYTES => (item: Any) => if (item == null) {
        null
      } else {
        val bytes = item.asInstanceOf[ByteBuffer]
        val javaBytes = new Array[Byte](bytes.remaining)
        bytes.get(javaBytes)
        javaBytes
      }
      case RECORD =>
        val fieldConverters = schema.getFields.map(f => createConverterToSQL(f.schema))
        (item: Any) => if (item == null) {
          null
        } else {
          val record = item.asInstanceOf[GenericRecord]
          val converted = new Array[Any](fieldConverters.size)
          var idx = 0
          while (idx < fieldConverters.size) {
            converted(idx) = fieldConverters.apply(idx)(record.get(idx))
            idx += 1
          }
          Row.fromSeq(converted.toSeq)
        }
      case ARRAY =>
        val elementConverter = createConverterToSQL(schema.getElementType)
        (item: Any) => if (item == null) {
          null
        } else {
          try {
            item.asInstanceOf[GenericData.Array[Any]].map(elementConverter)
          } catch {
            case e: Throwable =>
              item.asInstanceOf[util.ArrayList[Any]].map(elementConverter)
          }
        }
      case MAP =>
        val valueConverter = createConverterToSQL(schema.getValueType)
        (item: Any) => if (item == null) {
          null
        } else {
          item.asInstanceOf[HashMap[Any, Any]].map(x => (x._1.toString, valueConverter(x._2))).toMap
        }
      case UNION =>
        if (schema.getTypes.exists(_.getType == NULL)) {
          val remainingUnionTypes = schema.getTypes.filterNot(_.getType == NULL)
          if (remainingUnionTypes.size == 1) {
            createConverterToSQL(remainingUnionTypes.get(0))
          } else {
            createConverterToSQL(Schema.createUnion(remainingUnionTypes))
          }
        } else schema.getTypes.map(_.getType) match {
          case Seq(t1, t2) if Set(t1, t2) == Set(INT, LONG) =>
            (item: Any) => {
              item match {
                case l: Long => l
                case i: Int => i.toLong
                case null => null
              }
            }
          case Seq(t1, t2) if Set(t1, t2) == Set(FLOAT, DOUBLE) =>
            (item: Any) => {
              item match {
                case d: Double => d
                case f: Float => f.toDouble
                case null => null
              }
            }
          case other => throw new SchemaConversionException(
            s"This mix of union types is not supported (see README): $other")
        }
      case other => throw new SchemaConversionException(s"invalid avro type: $other")
    }
  }
}


object AvroSerde {
  // We only handle top level is record or primary type now
  def serialize(input: Any, schema: Schema): Array[Byte]= {
    val bao = new ByteArrayOutputStream()
    try {
      val writer = new GenericDatumWriter[Any](schema)
      val encoder: BinaryEncoder = EncoderFactory.get().directBinaryEncoder(bao, null)
      writer.write(input, encoder)
      bao.toByteArray()
    } catch {
      case e: IOException => throw new Exception(s"Exception while creating byte array for Avro schema ${schema}")
    } finally {
      if(bao != null) {
        bao.close()
      }
    }
  }

  def deserialize(input: Array[Byte], schema: Schema): Any = {
    val reader2: DatumReader[Any] = new GenericDatumReader[Any](schema)
    val bai2 = new ByteArrayInputStream(input)
    val decoder2: BinaryDecoder = DecoderFactory.get().directBinaryDecoder(bai2, null)
    val res: Any = reader2.read(null, decoder2)
    res
  }
}

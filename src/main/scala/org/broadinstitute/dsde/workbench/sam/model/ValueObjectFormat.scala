package org.broadinstitute.dsde.workbench.sam.model

import spray.json.{DeserializationException, JsString, JsValue, RootJsonFormat}

/**
  * Created by dvoet on 6/26/17.
  */
trait ValueObject {
  val value: String

  override def toString: String = value
}

case class ValueObjectFormat[T <: ValueObject](create: String => T) extends RootJsonFormat[T] {
  def read(obj: JsValue): T = obj match {
    case JsString(value) => create(value)
    case _ => throw new DeserializationException(s"could not deserialize value object $obj")
  }

  def write(obj: T): JsValue = JsString(obj.value)
}

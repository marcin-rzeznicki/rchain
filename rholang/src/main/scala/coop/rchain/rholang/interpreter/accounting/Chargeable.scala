package coop.rchain.rholang.interpreter.accounting

import coop.rchain.models.Channel
import coop.rchain.models.Channel.ChannelInstance.Quote
import scalapb.{GeneratedMessage, Message}

trait Chargeable[A] {
  def termSize(a: A): Int
}

object Chargeable {
  def apply[A](implicit ev: Chargeable[A]): Chargeable[A] = ev
  implicit def fromProtobuf[T <: GeneratedMessage with Message[T]] =
    new Chargeable[T] {
      override def termSize(a: T): Int = a.serializedSize
    }

  implicit val chargeableQuote: Chargeable[Quote] = new Chargeable[Quote] {
    override def termSize(a: Quote): Int = Channel(a).serializedSize
  }
}

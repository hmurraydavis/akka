/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.serialization.SerializationExtension

/**
 * Utilities to test serialization.
 */
class SerializationTestKit(system: ActorSystem[_]) {

  /**
   * Verify serialization roundtrip.
   * Throws exception from serializer if `obj` can't be serialized and deserialized.
   * Also tests that the deserialized  object is equal to `obj`, and if not an
   * `AssertionError` is thrown.
   *
   * @param obj the object to verify
   * @return the deserialized object
   */
  def verifySerialization[M](obj: M): M =
    verifySerialization(obj, assertEquality = true)

  /**
   * Verify serialization roundtrip.
   * Throws exception from serializer if `obj` can't be serialized and deserialized.
   *
   * @param obj the object to verify
   * @param assertEquality if `true` the deserialized  object is verified to be equal to `obj`,
   *                       and if not an `AssertionError` is thrown
   * @return the deserialized object
   */
  def verifySerialization[M](obj: M, assertEquality: Boolean): M = {
    import akka.actor.typed.scaladsl.adapter._
    val result =
      SerializationExtension(system.toUntyped).verifySerialization(obj.asInstanceOf[AnyRef]).asInstanceOf[M]
    if (assertEquality && result != obj)
      throw new AssertionError(s"verifySerialization expected $obj, but was $result")
    result
  }
}

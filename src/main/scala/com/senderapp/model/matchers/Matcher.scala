package com.senderapp.model.matchers

import com.senderapp.utils.Utils._
import com.typesafe.config.{ Config, ConfigObject, ConfigValue, ConfigValueType }
import spray.json.JsValue

/**
 * Created by sergeykhruschak on 6/24/16.
 */
trait Matcher {
  def matches(v: JsValue): Boolean
}

case class EqMatcher(path: String, pattern: ConfigValue) extends Matcher {
  val unwrapped = pattern.unwrapped().toString

  override def matches(v: JsValue) = {
    v.pathOpt(path).map(js => js.unwrap.toString).contains(unwrapped)
  }

  override def toString = s"$path = $unwrapped"
}

case class ExistsMatcher(path: String, exists: Boolean) extends Matcher {
  override def matches(v: JsValue) = v.pathOpt(path).isDefined == exists
  override def toString = s"$path ${if (exists) "exists" else "not exists"}"
}

case class RegexMatcher(path: String, regex: String) extends Matcher {
  override def matches(v: JsValue) = v.pathOpt(path).exists(js => js.unwrap.toString.matches(regex))
  override def toString = s"$path matches $regex"
}

object Matchers {

  def fromConfig(v: ConfigValue, path: String, confRoot: Config): Option[Matcher] = {
    if (tryExistsMatcher(v, path, confRoot)) {
      Some(ExistsMatcher(path, v.asInstanceOf[ConfigObject].get("$exists").unwrapped().asInstanceOf[Boolean]))
    } else if (tryEqMatcher(v, path, confRoot)) {
      Some(EqMatcher(path, v))
    } else if (tryRegExpMatcher(v, path, confRoot)) {
      Some(RegexMatcher(path, v.asInstanceOf[ConfigObject].get("$regex").unwrapped().asInstanceOf[String]))
    } else {
      None
    }
  }

  def tryEqMatcher(v: ConfigValue, path: String, confRoot: Config): Boolean = {
    import ConfigValueType._
    Seq(BOOLEAN, STRING, NUMBER, NULL).contains(v.valueType())
  }

  def tryExistsMatcher(v: ConfigValue, path: String, confRoot: Config): Boolean =
    v.isInstanceOf[ConfigObject] && v.asInstanceOf[ConfigObject].containsKey("$exists")

  def tryRegExpMatcher(v: ConfigValue, path: String, confRoot: Config): Boolean = {
    v.isInstanceOf[ConfigObject] && v.asInstanceOf[ConfigObject].containsKey("$regex")
  }

}
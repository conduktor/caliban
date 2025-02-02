package caliban.schema

import caliban.introspection.adt._
import caliban.parsing.adt.Directive

import scala.annotation.tailrec

object Types {

  /**
   * Creates a new scalar type with the given name.
   */
  def makeScalar(
    name: String,
    description: Option[String] = None,
    specifiedBy: Option[String] = None,
    directives: Option[List[Directive]] = None
  ): __Type =
    __Type(__TypeKind.SCALAR, Some(name), description, specifiedBy = specifiedBy, directives = directives)

  val boolean: __Type = makeScalar("Boolean")
  val string: __Type  = makeScalar("String")
  val int: __Type     = makeScalar("Int")
  val long: __Type    = makeScalar("Long")
  val float: __Type   = makeScalar("Float")
  val double: __Type  = makeScalar("Double")

  def makeEnum(
    name: Option[String],
    description: Option[String],
    values: List[__EnumValue],
    origin: Option[String],
    directives: Option[List[Directive]] = None
  ): __Type =
    __Type(
      __TypeKind.ENUM,
      name,
      description,
      enumValues =
        args => if (args.includeDeprecated.getOrElse(false)) Some(values) else Some(values.filter(!_.isDeprecated)),
      origin = origin,
      directives = directives
    )

  def makeObject(
    name: Option[String],
    description: Option[String],
    fields: List[__Field],
    directives: List[Directive],
    origin: Option[String] = None,
    interfaces: () => Option[List[__Type]] = () => Some(Nil)
  ): __Type =
    __Type(
      __TypeKind.OBJECT,
      name,
      description,
      fields =
        args => if (args.includeDeprecated.getOrElse(false)) Some(fields) else Some(fields.filter(!_.isDeprecated)),
      interfaces = interfaces,
      directives = Some(directives),
      origin = origin
    )

  def makeField(
    name: String,
    description: Option[String],
    arguments: List[__InputValue],
    `type`: () => __Type,
    isDeprecated: Boolean = false,
    deprecationReason: Option[String] = None,
    directives: Option[List[Directive]] = None
  ): __Field =
    __Field(
      name,
      description,
      args =>
        if (args.includeDeprecated.getOrElse(false)) arguments
        else arguments.filter(!_.isDeprecated),
      `type`,
      isDeprecated,
      deprecationReason,
      directives
    )

  def makeInputObject(
    name: Option[String],
    description: Option[String],
    fields: List[__InputValue],
    origin: Option[String] = None,
    directives: Option[List[Directive]] = None
  ): __Type =
    __Type(
      __TypeKind.INPUT_OBJECT,
      name,
      description,
      inputFields = args =>
        if (args.includeDeprecated.getOrElse(false)) Some(fields)
        else Some(fields.filter(!_.isDeprecated)),
      origin = origin,
      directives = directives
    )

  def makeUnion(
    name: Option[String],
    description: Option[String],
    subTypes: List[__Type],
    origin: Option[String] = None,
    directives: Option[List[Directive]] = None
  ): __Type =
    __Type(
      __TypeKind.UNION,
      name,
      description,
      possibleTypes = Some(subTypes),
      origin = origin,
      directives = directives
    )

  def makeInterface(
    name: Option[String],
    description: Option[String],
    fields: () => List[__Field],
    subTypes: List[__Type],
    origin: Option[String] = None,
    directives: Option[List[Directive]] = None
  ): __Type =
    __Type(
      __TypeKind.INTERFACE,
      name,
      description,
      fields =
        args => if (args.includeDeprecated.getOrElse(false)) Some(fields()) else Some(fields().filter(!_.isDeprecated)),
      possibleTypes = Some(subTypes),
      origin = origin,
      directives = directives
    )

  /**
   * Returns a map of all the types nested within the given root type.
   */
  def collectTypes(t: __Type, existingTypes: List[__Type] = Nil): List[__Type] =
    t.kind match {
      case __TypeKind.SCALAR | __TypeKind.ENUM   =>
        t.name.fold(existingTypes)(_ => if (existingTypes.exists(same(t, _))) existingTypes else t :: existingTypes)
      case __TypeKind.LIST | __TypeKind.NON_NULL =>
        t.ofType.fold(existingTypes)(collectTypes(_, existingTypes))
      case _                                     =>
        val list1         =
          t.name.fold(existingTypes)(_ =>
            if (existingTypes.exists(same(t, _))) {
              existingTypes.map {
                case ex if same(ex, t) =>
                  ex.copy(interfaces =
                    () =>
                      (ex.interfaces(), t.interfaces()) match {
                        case (None, None)              => None
                        case (Some(interfaces), None)  => Some(interfaces)
                        case (None, Some(interfaces))  => Some(interfaces)
                        case (Some(left), Some(right)) =>
                          Some(left ++ right.filterNot(t => left.exists(_.name == t.name)))
                      }
                  )
                case other             => other
              }
            } else t :: existingTypes
          )
        val embeddedTypes =
          t.allFields.flatMap(f => f.`type` :: f.allArgs.map(_.`type`)) ++
            t.allInputFields.map(_.`type`) ++
            t.interfaces().getOrElse(Nil).map(() => _)
        val list2         = embeddedTypes.foldLeft(list1) { case (types, f) =>
          val t = innerType(f())
          t.name.fold(types)(_ => if (existingTypes.exists(same(t, _))) types else collectTypes(t, types))
        }
        t.possibleTypes.getOrElse(Nil).foldLeft(list2) { case (types, subtype) => collectTypes(subtype, types) }
    }

  /**
   * Tries to find a common widened type among a list of fields.
   *
   * @param l a list of fields to unify
   * @return the unified type if one could be found
   */
  def unify(l: List[__Field]): Option[__Type] =
    l.headOption.flatMap { first =>
      val args                            = first.allArgs.map(_._type)
      def _unify(f2: __Field)(t1: __Type) =
        if (
          args.length == f2.allArgs.length &&
          args.zip(f2.allArgs.map(_._type)).forall(v => same(v._1, v._2))
        )
          unify(t1, f2._type)
        else None

      l.drop(1).foldLeft(Option(first._type))((acc, t) => acc.flatMap(_unify(t)))
    }

  /**
   * Tries to unify two types by widening them to a common supertype.
   *
   * @example {{{unify(string, makeNonNull(string)) // => Some(__Type(SCALAR, Some("String")))}}}
   * @param t1 type second type to unify
   * @param t2 the first type to unify
   * @return the unified type if one could be found
   */
  def unify(t1: __Type, t2: __Type): Option[__Type] =
    if (same(t1, t2)) Option(t1)
    else
      (t1.kind, t2.kind) match {
        case (__TypeKind.NON_NULL, _) => t1.ofType.flatMap(unify(_, t2))
        case (_, __TypeKind.NON_NULL) => t2.ofType.flatMap(unify(_, t1))
        case _                        => None
      }

  @tailrec
  def same(t1: __Type, t2: __Type): Boolean =
    if (t1.kind == t2.kind && t1.ofType.nonEmpty)
      same(t1.ofType.getOrElse(t1), t2.ofType.getOrElse(t2))
    else
      t1.name == t2.name && t1.kind == t2.kind && (t1.origin.isEmpty || t2.origin.isEmpty || t1.origin == t2.origin)

  def innerType(t: __Type): __Type = t.ofType.fold(t)(innerType)

  def listOf(t: __Type): Option[__Type] =
    t.kind match {
      case __TypeKind.LIST     => t.ofType
      case __TypeKind.NON_NULL => t.ofType.flatMap(listOf)
      case _                   => None
    }

  def name(t: __Type): String =
    (t.kind match {
      case __TypeKind.LIST     => t.ofType.map("ListOf" + name(_))
      case __TypeKind.NON_NULL => t.ofType.map(name)
      case _                   => t.name
    }).getOrElse("")
}

package org.broadinstitute.dsde.workbench.sam.util

import java.util
import javax.naming._
import javax.naming.directory._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait JndiSupport {
  protected def getContext(url: String, user: String, password: String): InitialDirContext = {
    val env = new util.Hashtable[String, String]()
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    env.put(Context.PROVIDER_URL, url)
    env.put(Context.SECURITY_PRINCIPAL, user)
    env.put(Context.SECURITY_CREDENTIALS, password)

    // enable connection pooling
    env.put("com.sun.jndi.ldap.connect.pool", "true")

    new InitialDirContext(env)
  }

  protected def withContext[T](url: String, user: String, password: String)(op: InitialDirContext => T)(implicit executionContext: ExecutionContext): Future[T] = Future {
    val ctx = getContext(url, user, password)
    val t = Try(op(ctx))
    ctx.close()
    t.get
  }

  protected def createAttributeDefinition(schema: DirContext, numericOID: String, name: String, description: String, singleValue: Boolean, equality: Option[String] = None, ordering: Option[String] = None, syntax: Option[String] = None) = {
    val attributes = new BasicAttributes(true)
    attributes.put("NUMERICOID", numericOID)
    attributes.put("NAME", name)
    attributes.put("DESC", description)
    equality.foreach(attributes.put("EQUALITY", _))
    ordering.foreach(attributes.put("ORDERING", _))
    syntax.foreach(attributes.put("SYNTAX", _))
    if (singleValue) attributes.put("SINGLE-VALUE", singleValue.toString) // note absence of this attribute means multi-value and presence means single, value does not matter
    schema.createSubcontext(s"AttributeDefinition/$name", attributes)
  }

}

/**
 * this does nothing but throw new OperationNotSupportedException but makes extending classes nice
 */
trait BaseDirContext extends DirContext {
  override def getAttributes(name: Name): Attributes = throw new OperationNotSupportedException
  override def getAttributes(name: String): Attributes = throw new OperationNotSupportedException
  override def getAttributes(name: Name, attrIds: Array[String]): Attributes = throw new OperationNotSupportedException
  override def getAttributes(name: String, attrIds: Array[String]): Attributes = throw new OperationNotSupportedException
  override def getSchema(name: Name): DirContext = throw new OperationNotSupportedException
  override def getSchema(name: String): DirContext = throw new OperationNotSupportedException
  override def createSubcontext(name: Name, attrs: Attributes): DirContext = throw new OperationNotSupportedException
  override def createSubcontext(name: String, attrs: Attributes): DirContext = throw new OperationNotSupportedException
  override def modifyAttributes(name: Name, mod_op: Int, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def modifyAttributes(name: String, mod_op: Int, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def modifyAttributes(name: Name, mods: Array[ModificationItem]): Unit = throw new OperationNotSupportedException
  override def modifyAttributes(name: String, mods: Array[ModificationItem]): Unit = throw new OperationNotSupportedException
  override def getSchemaClassDefinition(name: Name): DirContext = throw new OperationNotSupportedException
  override def getSchemaClassDefinition(name: String): DirContext = throw new OperationNotSupportedException
  override def rebind(name: Name, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def rebind(name: String, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def bind(name: Name, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def bind(name: String, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def search(name: Name, matchingAttributes: Attributes, attributesToReturn: Array[String]): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, matchingAttributes: Attributes, attributesToReturn: Array[String]): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: Name, matchingAttributes: Attributes): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, matchingAttributes: Attributes): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: Name, filter: String, cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, filter: String, cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: Name, filterExpr: String, filterArgs: Array[AnyRef], cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, filterExpr: String, filterArgs: Array[AnyRef], cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def getNameInNamespace: String = throw new OperationNotSupportedException
  override def addToEnvironment(propName: String, propVal: scala.Any): AnyRef = throw new OperationNotSupportedException
  override def rename(oldName: Name, newName: Name): Unit = throw new OperationNotSupportedException
  override def rename(oldName: String, newName: String): Unit = throw new OperationNotSupportedException
  override def lookup(name: Name): AnyRef = throw new OperationNotSupportedException
  override def lookup(name: String): AnyRef = throw new OperationNotSupportedException
  override def destroySubcontext(name: Name): Unit = throw new OperationNotSupportedException
  override def destroySubcontext(name: String): Unit = throw new OperationNotSupportedException
  override def composeName(name: Name, prefix: Name): Name = throw new OperationNotSupportedException
  override def composeName(name: String, prefix: String): String = throw new OperationNotSupportedException
  override def createSubcontext(name: Name): Context = throw new OperationNotSupportedException
  override def createSubcontext(name: String): Context = throw new OperationNotSupportedException
  override def unbind(name: Name): Unit = throw new OperationNotSupportedException
  override def unbind(name: String): Unit = throw new OperationNotSupportedException
  override def removeFromEnvironment(propName: String): AnyRef = throw new OperationNotSupportedException
  override def rebind(name: Name, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def rebind(name: String, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def getEnvironment: util.Hashtable[_, _] = throw new OperationNotSupportedException
  override def list(name: Name): NamingEnumeration[NameClassPair] = throw new OperationNotSupportedException
  override def list(name: String): NamingEnumeration[NameClassPair] = throw new OperationNotSupportedException
  override def close(): Unit = throw new OperationNotSupportedException
  override def lookupLink(name: Name): AnyRef = throw new OperationNotSupportedException
  override def lookupLink(name: String): AnyRef = throw new OperationNotSupportedException
  override def getNameParser(name: Name): NameParser = throw new OperationNotSupportedException
  override def getNameParser(name: String): NameParser = throw new OperationNotSupportedException
  override def bind(name: Name, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def bind(name: String, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def listBindings(name: Name): NamingEnumeration[Binding] = throw new OperationNotSupportedException
  override def listBindings(name: String): NamingEnumeration[Binding] = throw new OperationNotSupportedException
}

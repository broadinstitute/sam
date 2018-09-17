package org.broadinstitute.dsde.workbench.sam.util

import java.util

import javax.naming._
import javax.naming.directory._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// comment out deprecation annotation since it's still actively used in `JndiSchemaDao` and that's not going to be changed anytime soon
//@deprecated(message = "use LdapSupport instead", since = "unknown")
// new code should use LdapSupport instead
trait JndiSupport {
  private val batchSize = 1000

  protected def getContext(url: String, user: String, password: String): InitialDirContext = {
    val env = new util.Hashtable[String, String]()
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    env.put(Context.PROVIDER_URL, url)
    env.put(Context.SECURITY_PRINCIPAL, user)
    env.put(Context.SECURITY_CREDENTIALS, password)

    // enable connection pooling
//    env.put("com.sun.jndi.ldap.connect.pool", "true")

    new InitialDirContext(env)
  }

  /**
    * Given a possibly large collection of inputs, splits input into batches and calls op on each batch.
    * @param url
    * @param user
    * @param password
    * @param input
    * @param op function of type (Seq[T])(InitialDirContext) => Seq[R], a function that takes a batch which produces a
    *           function that takes an InitialDirContext that produces the results
    * @param executionContext
    * @tparam T type of inputs
    * @tparam R type of results
    * @return aggregated results of calling op for each batch
    */
  def batchedLoad[T, R](url: String, user: String, password: String)(input: Seq[T])(op: (Seq[T]) => (InitialDirContext) => Seq[R])(implicit executionContext: ExecutionContext): Future[Seq[R]] = {
    if (input.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      Future.sequence(input.grouped(batchSize).map { batch =>
        withContext(url, user, password)(op(batch))
      }).map(_.flatten.toSeq)
    }
  }

  protected def withContext[T](url: String, user: String, password: String)(op: InitialDirContext => T)(implicit executionContext: ExecutionContext): Future[T] = Future {
    val ctx = getContext(url, user, password)
    val t = Try(op(ctx))
    ctx.close()
    t.get
  }

  /**
    * Use this implicit conversion class and following call to extractResultsAndClose
    * instead of JavaConverters and call to asScala
    * because it makes sure the NamingEnumeration is closed and connections are not leaked.
    * @param results results from a search, etc.
    * @return object that can be used to safely handle and close NamingEnumeration
    */
  protected implicit class NamingEnumCloser[T](results: NamingEnumeration[T]) {
    /**
      * copy results enum into a Seq then close the enum
      * @return results
      */
    def extractResultsAndClose: Seq[T] = {
      import scala.collection.JavaConverters._
      try {
        Seq(results.asScala.toSeq:_*)
      } finally {
        results.close()
      }
    }
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

package org.broadinstitute.dsde.workbench.sam.directory
import cats.implicits._
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupIdentity, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr
import org.broadinstitute.dsde.workbench.sam.util.LdapSupport
import org.broadinstitute.dsde.workbench.sam.util.cache.Cache

trait LdapGroupSupport {
  this: LdapSupport =>

  protected val memberOfCache: Cache[IO, WorkbenchSubject, Set[String]]

  protected def ldapLoadMemberOf(subject: WorkbenchSubject): IO[Set[String]] = {
    memberOfCache.get(subject).flatMap {
      case None =>
        for {
          entry <- executeLdap(IO(ldapConnectionPool.getEntry(subjectDn(subject), Attr.memberOf)))
          memberOfs = Option(entry).flatMap(e => Option(getAttributes(e, Attr.memberOf))).getOrElse(Set.empty)
          _ <- memberOfCache.put(subject, memberOfs)
        } yield {
          memberOfs
        }

      case Some(memberOfs) => IO.pure(memberOfs)
    }
  }

  protected def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit] = evictIsMemberOfCacheRecursive(subject, Set.empty)

  private def evictIsMemberOfCacheRecursive(subject: WorkbenchSubject, visited: Set[WorkbenchSubject]): IO[Unit] = {
    if (visited.contains(subject)) {
      IO.unit
    } else {
      val evictGroup = subject match {
        //  if a group is being evicted, need to evict all of it direct members
        case groupId: WorkbenchGroupIdentity =>
          for {
            members <- listDirectMembers(groupId)
            _ <- members.toList.traverse(m => evictIsMemberOfCacheRecursive(m, visited + subject))
          } yield ()

        case _ => IO.unit
      }

      evictGroup *> memberOfCache.remove(subject)
    }
  }

  def listFlattenedMembers(groupId: WorkbenchGroupIdentity, visitedGroupIds: Set[WorkbenchGroupIdentity] = Set.empty): IO[Set[WorkbenchUserId]] = {
    for {
      directMembers <- listDirectMembers(groupId)
      users = directMembers.collect { case subject: WorkbenchUserId => subject }
      subGroups = directMembers.collect { case subject: WorkbenchGroupIdentity => subject }
      updatedVisitedGroupIds = visitedGroupIds ++ subGroups
      nestedUsers <- (subGroups -- visitedGroupIds).toList.traverse(subGroupId => listFlattenedMembers(subGroupId, updatedVisitedGroupIds))
    } yield {
      users ++ nestedUsers.flatten
    }
  }

  def listDirectMembers(groupId: WorkbenchGroupIdentity): IO[Set[WorkbenchSubject]] = {
    for {
      entry <- executeLdap(IO(ldapConnectionPool.getEntry(groupDn(groupId), Attr.uniqueMember)))
    } yield {
      Option(entry).map(e => getAttributes(e, Attr.uniqueMember).map(dnToSubject)).getOrElse(Set.empty)
    }
  }

}

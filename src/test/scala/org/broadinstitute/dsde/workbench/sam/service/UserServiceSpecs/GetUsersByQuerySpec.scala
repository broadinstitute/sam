package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}

import scala.concurrent.ExecutionContextExecutor
import scala.util.Random

class GetUsersByQuerySpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  /*TODO: Write tests for the following:
   *get users without any params -> should return a list of all users up to a max 1000
   *get users with valid limit param -> should return a list of users up to limit
   *get users with limit param exceeding max -> should return a list of users up to 1000 (max)
   *get users with limit param below min -> should return a list of users up to 1 (min)
          get users by only userId with an existing corresponding user -> should return 1 corresponding user with that userId
          get users by only googleSubjectId with an existing corresponding user -> should return 1 corresponding user with that googleSubjectId
          get users by only azureB2CId with an existing corresponding user -> should return 1 corresponding user with that azureB2CId
          get users by userId, googleSubjectId, and azureB2CID that are all the same with an existing corresponding user -> should return 1 corresponding user with those fields
          get users by userId, googleSubjectId, and azureB2CID that are all unique with existing corresponding users -> should return 3 corresponding users with each matching field
   */

  describe("When getting") {
    describe("existing user records") {
      describe("without any parameters") {
        it("should return a collection of user records up to the default maximum (10)") {
          // Arrange
          val numRecords = 1500
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build

          // Act
          val response = runAndWait(userServiceBuilder.getUsersByQuery(None, None, None, None, samRequestContext))

          // Assert
          assert(response.size equals 10, s"Getting users with no parameters should return 10 user records, instead was: ${response.size}")
        }
      }
      describe("with a limit") {
        describe("that is valid") {
          it("should return a collection of user records up to the limit (500)") {
            // Arrange
            val numRecords = 1500
            val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

            val userServiceBuilder = TestUserServiceBuilder()
              .withAllUsersGroup(allUsersGroup)
              .withExistingUsers(userRecords)
              .build

            val limit = 500
            // Act
            val response = runAndWait(userServiceBuilder.getUsersByQuery(None, None, None, Option(limit), samRequestContext))

            // Assert
            assert(response.size <= limit && response.nonEmpty, s"Getting users with valid [1,1000] limit should return up to $limit user records")
          }
        }
        describe("that is over the maximum limit") {
          it("should return a collection of user records up to the maximum limit (1000)") {
            // Arrange
            val numRecords = 1500
            val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

            val userServiceBuilder = TestUserServiceBuilder()
              .withAllUsersGroup(allUsersGroup)
              .withExistingUsers(userRecords)
              .build

            val limit = 2000
            // Act
            val response = runAndWait(userServiceBuilder.getUsersByQuery(None, None, None, Option(limit), samRequestContext))

            // Assert
            assert(
              response.size <= 1000 && response.nonEmpty,
              s"Getting users with a limit exceeding maximum limit should return up to the maximum limit (1000) user records"
            )
          }
        }
        describe("that is less than 1") {
          it("should return a collection of user records up to 1") {
            // Arrange
            val numRecords = 1500
            val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

            val userServiceBuilder = TestUserServiceBuilder()
              .withAllUsersGroup(allUsersGroup)
              .withExistingUsers(userRecords)
              .build

            val limit = -5
            // Act
            val response = runAndWait(userServiceBuilder.getUsersByQuery(None, None, None, Option(limit), samRequestContext))

            // Assert
            assert(response.size <= 1 && response.nonEmpty, s"Getting users with a limit less than 1 should return up to the 1 user record")
          }
        }
      }
      describe("with a userId") {
        it("should return a collection of 1 user record with that userId") {
          // Arrange
          val numRecords = 100
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build
          val user = userRecords(Random.nextInt(userRecords.size))
          // Act
          val response = runAndWait(userServiceBuilder.getUsersByQuery(Option(user.id), None, None, None, samRequestContext))

          // Assert
          assert(response.size equals 1, s"Getting users with a userId should return 1 user record")
          assert(response.head.id equals user.id, s"Getting users with a userId should return the corresponding user with that userId")
        }
      }
      describe("with a googleSubjectId") {
        it("should return a collection of 1 user record with that googleSubjectId") {
          // Arrange
          val numRecords = 100
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build
          val user = userRecords(Random.nextInt(userRecords.size))
          // Act
          val response = runAndWait(userServiceBuilder.getUsersByQuery(None, user.googleSubjectId, None, None, samRequestContext))

          // Assert
          assert(response.size equals 1, s"Getting users with a googleSubjectId should return 1 user record")
          assert(response.head.id equals user.id, s"Getting users with a googleSubjectId should return the corresponding user with that googleSubjectId")
        }
      }
      describe("with a azureB2CId") {
        it("should return a collection of 1 user record with that azureB2CId") {
          // Arrange
          val numRecords = 100
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build
          val user = userRecords(Random.nextInt(userRecords.size))
          // Act
          val response = runAndWait(userServiceBuilder.getUsersByQuery(None, None, user.azureB2CId, None, samRequestContext))

          // Assert
          assert(response.size equals 1, s"Getting users with a azureB2CId should return 1 user record")
          assert(response.head.id equals user.id, s"Getting users with a azureB2CId should return the corresponding user with that azureB2CId")
        }
      }
      describe("with the same userId, googleSubjectId, and azureB2CId") {
        it("should return a collection of 1 user record with either the userId, googleSubjectId, or azureB2CId matching") {
          // Arrange
          val numRecords = 100
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build
          val user = userRecords(Random.nextInt(userRecords.size))
          // Act
          val response = runAndWait(
            userServiceBuilder.getUsersByQuery(
              Option(user.id),
              Option(GoogleSubjectId(user.id.value)),
              Option(AzureB2CId(user.id.value)),
              None,
              samRequestContext
            )
          )

          // Assert
          assert(response.size equals 1, s"Getting users with the same userId, googleSubjectId, azureB2CId should return 1 user record")
          assert(
            response.head.id equals user.id,
            s"Getting users with the same userId, googleSubjectId, azureB2CId should return the corresponding user with one of their ids matching"
          )
        }
      }
      describe("with unique userId, googleSubjectId, and azureB2CId which correspond to the same user") {
        it("should return a collection of 1 user record with the userId, googleSubjectId, and azureB2CId matching") {
          // Arrange
          val numRecords = 100
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build
          val user = userRecords(Random.nextInt(userRecords.size))
          // Act
          val response = runAndWait(userServiceBuilder.getUsersByQuery(Option(user.id), user.googleSubjectId, user.azureB2CId, None, samRequestContext))

          // Assert
          assert(
            response.size equals 1,
            s"Getting users with a userId, googleSubjectId, azureB2CId which correspond to the same user should return 1 user record"
          )
          assert(
            response.head.id == user.id && response.head.googleSubjectId == user.googleSubjectId && response.head.azureB2CId == user.azureB2CId,
            s"Getting users with a userId, googleSubjectId, azureB2CId which correspond to the same user should return the corresponding user with all ids matching"
          )
        }
      }
      describe("with unique userId, googleSubjectId, and azureB2CId which correspond to different users") {
        it("should return a collection of 3 user records with the userId, googleSubjectId, and azureB2CId each matching one user record") {
          // Arrange
          val numRecords = 100
          val userRecords = Seq.iterate(genWorkbenchUserBoth.sample.get, numRecords)(_ => genWorkbenchUserBoth.sample.get)

          val userServiceBuilder = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUsers(userRecords)
            .build
          val user1 = userRecords.head
          val user2 = userRecords(1)
          val user3 = userRecords(2)
          // Act
          val response = runAndWait(userServiceBuilder.getUsersByQuery(Option(user1.id), user2.googleSubjectId, user3.azureB2CId, None, samRequestContext))

          // Assert
          assert(
            response.size equals 3,
            s"Getting users with a userId, googleSubjectId, azureB2CId which correspond to different users should return 3 user records"
          )
          assert(
            response.contains(user1) && response.contains(user2) && response.contains(user3),
            s"Getting users with a userId, googleSubjectId, azureB2CId which correspond to different users should return the corresponding users with matching ids"
          )
        }
      }
    }
    describe("user records that do not exist") {
      it("should be unsuccessful") {
        // Arrange
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .build

        // Act
        val response = runAndWait(userService.getUsersByQuery(None, None, None, None, samRequestContext))

        // Assert
        assert(response.isEmpty, "Getting nonexistent users should not find any users")
      }
    }
  }

}

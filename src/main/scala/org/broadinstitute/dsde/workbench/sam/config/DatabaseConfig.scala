package org.broadinstitute.dsde.workbench.sam.config

final case class SamDatabaseConfig(samRead: DatabaseConfig, samWrite: DatabaseConfig, samBackground: DatabaseConfig)

final case class DatabaseConfig(
                                 dbName: Symbol,
                                 poolInitialSize: Int,
                                 poolMaxSize: Int,
                                 poolConnectionTimeoutMillis: Int,
                                 driver: String,
                                 url: String,
                                 user: String,
                                 password: String)
# Configuring SAM with Environment Variables

HOCON supports combining conf files with fallbacks, which means if a value is not defined in `resource-a.conf`,
but is defined in `resource-b.conf`, then `ConfigFactory.load("resource-a").withFallback(ConfigFactory.load("resource-b"))`
will contain values from both resource files. For any variable in both, that variable will be populated by `resource-a.conf`.
Additionally, we can provide environment variables to populate the resource
file by defining `db.sam_read.user=${?SAM_READ_USER}`, which will only load the value if the environment variable is defined.
With this, we can define an `env.conf`, filled with optional environment variables, and load that with the default config
as a fallback, meaning environment variables that are defined will be read, and those that aren't will be populated by
the default conf file.
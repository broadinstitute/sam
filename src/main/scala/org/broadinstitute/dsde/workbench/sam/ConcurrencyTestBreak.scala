package org.broadinstitute.dsde.workbench.sam

// CORE-69: deliberate compile error for the CI concurrency test of broadinstitute/sam#1779. Never merge.
object ConcurrencyTestBreak { val x: Int = "not an int" }

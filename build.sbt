import AssemblyKeys._test in assembly := {} testOptions in Test += Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "3")
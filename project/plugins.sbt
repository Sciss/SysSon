addSbtPlugin( "com.eed3si9n" % "sbt-buildinfo" % "0.2.2" )  // provides version information to copy into main class

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.8.8")     // builds standalone jar for Windows and Linux

addSbtPlugin("de.sciss" % "sbt-appbundle" % "1.0.1")        // builds standalone application for OS X

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.4.0") // for setting up an IntelliJ IDEA project (development)

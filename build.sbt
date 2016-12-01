name := """reactive-lab4"""

version := "1.1"

scalaVersion := "2.11.8"

resolvers += "bseibel at bintray" at "http://dl.bintray.com/bseibel/release"
resolvers += "jdgoldie at bintray" at "http://dl.bintray.com/jdgoldie/maven"
resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/maven-releases/"



libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.11",
  "com.typesafe.akka" %% "akka-persistence" % "2.4.11",
  "org.iq80.leveldb"            % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"

)
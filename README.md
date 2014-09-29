# metaHathi 

A Scalatra app to import downloaded Hathi metadata into a running OpenRefine instance.


## Build & Run

These instructions are for OS X. You should be able to do similar things on
Ubuntu.

### First get a few things:

    brew install git tomcat7 maven

### Build and deploy metaHathi
    
    git clone https://github.com/umd-mith/metaHathi
    cd metaHathi
    sbt package
    cp target/scala-2.10/metahathi_2.10-0.1.0.war /usr/local/Cellar/tomcat7/7.0.55/libexec/webapps/
    cd ..

### Build and deploy OpenRefine:

    git clone https://github.com/wikier/OpenRefine
    cd OpenRefine
    mvn package
    cp target/openrefine.war /usr/local/Cellar/tomcat7/7.0.55/libexec/webapps/

### View the app!

    catalina restart
    open http://localhost:8080/

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

### Source Data

metaHathi is configured to look for data to convert in a particular directory. 
By default metaHathi will look in this directory:

    /usr/share/metahathi/raw_data

If you would like to change it edit the `app.data` path in 
`src/main/resources/application.conf`. 

metaHathi expects to convert a particular flavor of JSON data.  You will need 
to run [hathi](https://github.com/umd-mith/hathi) which downloads data from 
the HathiTrust API to create that JSON data. Once you've generated it you 
will need to copy it to:

    /usr/share/metahathi/raw_data

### View the app!

    catalina restart
    open http://localhost:8080/

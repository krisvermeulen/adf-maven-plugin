adf-maven-plugin
================

This plugin scans a local Oracle Middleware folder for ADF libraries and deploys them to the specified remote repository.

The original source code in the package com.googlecode.mavenadf came from https://code.google.com/p/maven-adf and is slightly modified to use it from the DeployADFLibrariesMojo class.


Usage:
------

Install the plugin in your local repository:

```
mvn clean install
```

And execute the plugin from the command line:

```
mvn be.mindworx.maven.plugin:adf-maven-plugin:deploy-adf -DadfVersion=11.1.1.7.0 \
                                                         -DjdevHome=C:\Oracle\Middleware\jdeveloper \
                                                         -DrepositoryId=oracle \
                                                         -Durl=http://127.0.0.1:8081/nexus/content/repositories/oracle \
                                                         -DgroupIdPrefix=com.oracle.jdeveloper \
                                                         -DpackagingType=jdev-library
```


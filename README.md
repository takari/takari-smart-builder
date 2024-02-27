# Takari Smart Builder

## Set it up

with latest Maven 3.9.x line setting it up is simple.

Create in project root a file `.mvn/extensions.xml` with following content:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
    <extension>
        <groupId>io.takari.maven</groupId>
        <artifactId>takari-smart-builder</artifactId>
        <version>0.6.5</version>
    </extension>
</extensions>
```

This will make Maven load the Smart Builder extension (will resolve it first time). Then
to use it, do this:

```
$ mvn -b smart -T2C
```

Or alternatively, create `.mvn/maven.config` file (and place each configuration on separate line):

```
-b
smart
-T2C
```

And that it is.

## About

The Takari Smart Builder is a replacement scheduling projects builds in a Maven multi-module build. 

Documentation for usage and more is available in the Takari TEAM documentation at http://takari.io/book/index.html

## Reference and History

The original implementation of the Smart Builder came from Travis Downs and Brian Toal at [Salesforce][1] based on ideas
from the paper [Static vs. Dynamic List-Scheduling Performance Comparison][2] . Takari subsequently added the metrics
persistence and critical path scheduling.

[1]: http://salesforce.com
[2]: 4Hagras.pdf
[3]: https://github.com/takari/takari-local-repository

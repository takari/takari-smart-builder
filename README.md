# Takari Smart Builder

# Overview

The Takari Smart Builder is made from the fastest unicorns that exist. The primary difference between the standard multi-threaded scheduler in Maven and the Smart Builder scheduler is illustrated below.

![VsGraph](VsGraph.png)

The standard multi-threaded scheduler is dependency-depth based in that it builds everything at a given dependency-depth before continuing to the next level, while the Smart Builder scheduler is dependency-path based in that projects are aggressively built along a dependenty-path in topological order as upstream dependencies have been satisfied. 

In addition to the more aggressive build processing the Smart Builder can also optionally record project build times to determine your build's critical path. When possible we always want to try and schedule the critical path first.

**NOTE: You need to have Maven 3.2.1+ to use this extension.** 

## Installing

To use the Takari Smart Builder you must install it in Maven's `lib/ext` folder:

```
curl -O http://repo1.maven.org/maven2/io/takari/maven/takari-smart-builder/0.3.0/takari-smart-builder-0.3.0.jar

cp takari-smart-builder-0.3.0.jar $MAVEN_HOME/lib/ext
```

## Using

To use the Smart Builder invoke Maven like this:

```
mvn clean install --builder smart
```

### Using Critical Path Scheduling

If you want to try the critical path scheduling you need to create an `.mvn` directory at the root of your project for the `timing.properties` to be persisted. On subsequent runs the timing information will be used to try and schedule the longest chain, or critical path, first. Where possible we try to schedule project builds such that your build should take no longer than the critical path, though this is not always possible.

## Reference and History

The original implementation of the Smart Builder came from Travis Downs and Brian Toal at [Salesforce][1] based on ideas from the paper [Static vs. Dynamic List-Scheduling Performance Comparison][2]. Takari subsequently added the metrics persistence and critical path scheduling.

[1]: http://salesforce.com
[2]: 4Hagras.pdf
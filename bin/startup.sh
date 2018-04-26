#!/usr/bin/env bash


if [ -f ./bin/client.pid ] ; then
	echo "found client.pid , Please run stop.sh first ,then startup.sh"
    exit 1
fi

JAVA=$(which java)
echo $JAVA

JAVA_OPTS="-server -Xms1024m -Xmx1024m -XX:NewSize=256m -XX:MaxNewSize=256m -XX:MaxPermSize=128m "

# server mode in backgroud
$JAVA $JAVA_OPTS -cp "./lib/*" com.sync.process.task &

echo $! > ./bin/client.pid

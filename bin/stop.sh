#!/usr/bin/env bash

pidfile=./bin/client.pid

if [ ! -f "$pidfile" ];then
	echo "syncclient is not running. exists"
	exit
fi

pid=`cat $pidfile`

echo -e "`hostname`: stopping syncclient $pid ... "
kill -9 $pid

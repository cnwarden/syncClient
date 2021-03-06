package com.sync.process;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.huobi.tracker.client.KafkaAuditReporter;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.sync.common.GetProperties;
import com.sync.common.WriteLog;
import com.alibaba.fastjson.JSON;

import com.huobi.tracker.client.MessageTracker;

/**
 * kafka Producer
 * 
 * @author sasou <admin@php-gene.com> web:http://www.php-gene.com/
 * @version 1.0.0
 */
public class Kafka implements Runnable {
	private KafkaProducer<Integer, String> producer;
	private CanalConnector connector = null;
	private String thread_name = null;
	private String canal_destination = null;
	private MessageTracker tracker = null;
	private KafkaAuditReporter reporter = null;

	public Kafka(String name) {
		thread_name = "canal[" + name + "]:";
		canal_destination = name;
	}

	public void process() throws IOException {
        Properties props = new Properties();
        String kafkaServer = GetProperties.target.get(canal_destination).ip + ":" + GetProperties.target.get(canal_destination).port;
        String kafkaTopic = GetProperties.target.get(canal_destination).timetrackerTopic;
		props.put("bootstrap.servers", kafkaServer);
		props.put("client.id", canal_destination + "_Producer");
		props.put("key.serializer", "org.apache.kafka.common.serialization.IntegerSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

//        reporter = new KafkaAuditReporter(kafkaTopic, "AMA", "SyncClient"+System.currentTimeMillis(), kafkaServer, "1", null);
//        tracker = new MessageTracker(kafkaTopic, reporter, 1, 1000000000,  1);

        int batchSize = 5000;
		connector = CanalConnectors.newSingleConnector(
				new InetSocketAddress(GetProperties.canal.ip, GetProperties.canal.port), canal_destination,
				GetProperties.canal.username, GetProperties.canal.password);

		connector.connect();
		if (!"".equals(GetProperties.canal.filter)) {
			connector.subscribe(GetProperties.canal.filter);
		} else {
			connector.subscribe();
		}

		connector.rollback();

		try {
			producer = new KafkaProducer<>(props);
			WriteLog.write(canal_destination, thread_name + "Start-up success!");
			while (true) {
				Message message = connector.getWithoutAck(batchSize); // get batch num
				long batchId = message.getId();
				int size = message.getEntries().size();
				if (!(batchId == -1 || size == 0)) {
					if (syncEntry(message.getEntries())) {
						connector.ack(batchId); // commit
					} else {
						connector.rollback(batchId); // rollback
					}
				}
			}
		} finally {
			if (connector != null) {
				connector.disconnect();
				connector = null;
			}
			if (producer != null) {
				producer.close();
				producer = null;
			}
		}
	}

	public void run() {
		while (true) {
			try {
				process();
			} catch (Exception e) {
				WriteLog.write(canal_destination, thread_name + "canal link failure!");
			}
		}
	}

	private boolean syncEntry(List<Entry> entrys) {
		String topic = "";
		int no = 0;
		RecordMetadata metadata = null;
		boolean ret = true;
		for (Entry entry : entrys) {
			if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN
					|| entry.getEntryType() == EntryType.TRANSACTIONEND) {
				continue;
			}

			RowChange rowChage = null;
			try {
				rowChage = RowChange.parseFrom(entry.getStoreValue());
			} catch (Exception e) {
				throw new RuntimeException(
						thread_name + "parser of eromanga-event has an error , data:" + entry.toString(), e);
			}

			EventType eventType = rowChage.getEventType();
			Map<String, Object> data = new HashMap<String, Object>();
			Map<String, Object> head = new HashMap<String, Object>();
			head.put("binlog_file", entry.getHeader().getLogfileName());
			head.put("binlog_pos", entry.getHeader().getLogfileOffset());
			head.put("db", entry.getHeader().getSchemaName());
			head.put("table", entry.getHeader().getTableName());
			head.put("type", eventType);
			data.put("head", head);
			topic = "sync_" + entry.getHeader().getSchemaName() + "_" + entry.getHeader().getTableName();
			no = (int) entry.getHeader().getLogfileOffset();
			for (RowData rowData : rowChage.getRowDatasList()) {
				if (eventType == EventType.DELETE) {
					data.put("before", makeColumn(rowData.getBeforeColumnsList()));
				} else if (eventType == EventType.INSERT) {
					data.put("after", makeColumn(rowData.getAfterColumnsList()));
				} else {
					data.put("before", makeColumn(rowData.getBeforeColumnsList()));
					data.put("after", makeColumn(rowData.getAfterColumnsList()));
				}
				String text = JSON.toJSONString(data);
				if (eventType == EventType.INSERT || eventType == EventType.UPDATE) {
						producer.send(new ProducerRecord<>(topic, no, text), new Callback() {
							@Override
							public void onCompletion(RecordMetadata metadata, Exception e) {
								if (e != null) {
									WriteLog.write("Could not send message over Kafka for topic {}", e.toString());
								} else {
									if (GetProperties.system_debug > 0) {
										WriteLog.write(canal_destination + ".access", thread_name + "data(" + text + ")");
									}
								}
							}
						});
				}
			}
			data.clear();
			data = null;
		}
		return ret;
	}

	private Map<String, Object> makeColumn(List<Column> columns) {
		Map<String, Object> one = new HashMap<String, Object>();
		for (Column column : columns) {
			one.put(column.getName(), column.getValue());
		}
		return one;
	}
	
	protected void finalize() throws Throwable {
		if (connector != null) {
			connector.disconnect();
			connector = null;
		}
	}

}
#!/bin/bash

IP=$(hostname -i)
TOKEN_STORAGE=./docker-token
USER=$(whoami)

#nice for debugging and understanding -> localhost:10000
docker run -d -p 10000:9000 -v "/var/run/docker.sock:/var/run/docker.sock" --restart always portainer/portainer

#create docker swarm cluster
docker swarm init --advertise-addr $IP
mkdir -p "$TOKEN_STORAGE"
docker swarm join-token worker --quiet  > "$TOKEN_STORAGE/worker"
docker swarm join-token manager --quiet > "$TOKEN_STORAGE/manager"

#create docker swarm network
docker network create --driver overlay services

#preparation before starting the images
sudo mkdir -p /vol
sudo chown $USER /vol
sudo chgrp $USER /vol
mkdir -p /vol/mongo /vol/mongo/data /vol/mongo/config

#Add database --endpoint-mode dnsrr
docker service create --name mongo -p 27017:27017 --replicas 2 --network services --mount type=bind,source=/vol/mongo/data,target=/data/db --mount type=bind,source=/vol/mongo/config,target=/data/configdb mongo mongod --replSet mongoReplica

##on first mongo node
# docker exec -it mongo.*container-name* bash -c 'IP="$(hostname -i)"; echo "MasterIP: $IP"; mongo --eval "rs.initiate({ _id: \"mongoReplica\", members: [{ _id: 0, host: \"'$IP':27017\" }], settings: { getLastErrorDefaults: { w: \"majority\" }}})"'

## on all other nodes
# docker exec -it mongo.*container-name* bash -c 'echo -n "Enter ip of master [ENTER]: "; read IP; mongo --eval "rs.add(\"'$IP'\")"'
##to get ip of a docker container
#docker exec -it *container-name* hostname -i

#Add a zookeeper on a master nodes
# bin/zkCli.sh -server localhost:2181 ls /
docker service create --name zookeeper -p 2181:2181 --replicas 1 --constraint 'node.role == manager' --network services wurstmeister/zookeeper

#Add Kafka
# bin/kafka-topics.sh --zookeeper zookeeper:2181 --list
# bin/kafka-topics.sh --zookeeper zookeeper:2181 --create --replication-factor 1 --partitions 1 --topic helloworld
docker service create --name kafka -p 9092:9092 -e KAFKA_PORT=9092 -e KAFKA_ADVERTISED_PORT=9092 -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092 -e KAFKA_ZOOKEEPER_CONNECT=tasks.zookeeper:2181 -e "HOSTNAME_COMMAND=ip r | awk '{ ip[\$3] = \$NF } END { print ( ip[\"eth0\"] ) }'" --replicas 1 --network services wurstmeister/kafka

#Add visualization service
docker service create --name visualization -p 9000:9000 -e MONGO_ADDRESS=mongo --replicas 1 --network services timonback/newsforyou-visualization:latest

#Add spark master
docker service create --name spark-master --hostname spark-master -p 7077:7077 -p 8080:8080 --replicas 1 --network services singularities/spark start-spark master

#Add spark workers
docker service create --name spark-worker --hostname spark-worker --replicas 2 --network services singularities/spark start-spark worker spark-master

#Add spark task submitter (1min waiting inbetween runs) - example
#docker service create --name spark-task-submitter --replicas 1 --network services --restart-delay 1m singularities/spark spark-submit --class org.apache.spark.examples.SparkPi --master spark://spark-master:7077 /usr/local/spark-2.1.0/examples/jars/spark-examples_2.11-2.1.0.jar 10

#Add spark task submitter (1min waiting inbetween runs) - recommendation
docker service create --name spark-recommender-submitter -e MONGO_ADDRESS=mongo -e SPARK_ADDRESS=spark-master -e SPARK_JAR=/opt/docker/lib/newsforyou-recommendator.newsforyou-recommendator-latest.jar,/opt/docker/lib/org.mongodb.spark.mongo-spark-connector_2.11-2.0.0.jar,/opt/docker/lib/org.mongodb.mongo-java-driver-3.2.2.jar -e USE_DUMMY_DATA=true --replicas 1 --network services --restart-delay 1m timonback/newsforyou-recommendator:latest

#Add importer service (run every 24 hours)
docker service create --name importer -e MONGO_ADDRESS=mongo --replicas 1 --network services --restart-delay 24h timonback/newsforyou-importer:latest

#Add rating generator
docker service create --name streamer -e KAFKA_ADDRESS=kafka --replicas 1 --network services --restart-delay 1m timonback/newsforyou-streamer:latest


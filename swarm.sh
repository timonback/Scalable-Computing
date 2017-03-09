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

#Add database
docker service create --replicas 2 --network services --endpoint-mode dnsrr --mount type=bind,source=/vol/mongo/data,target=/data/db --mount type=bind,source=/vol/mongo/config,target=/data/configdb --name mongo mongo mongod --replSet mongoReplica

##on first mongo node
# docker exec -it mongo.*container-name* bash -c 'IP="$(hostname -i)"; echo "MasterIP: $IP"; mongo --eval "rs.initiate({ _id: \"mongoReplica\", members: [{ _id: 0, host: \"'$IP':27017\" }], settings: { getLastErrorDefaults: { w: \"majority\" }}})"'

## on all other nodes
# docker exec -it mongo.*container-name* bash -c 'echo -n "Enter ip of master [ENTER]: "; read IP; mongo --eval "rs.add(\"'$IP'\")"'
##to get ip of a docker container
#docker exec -it *container-name* hostname -i

#Add visualization service
docker service create --name visualization -p 9000:9000 -e MONGO_ADDRESS=mongo --replicas 1 --network services timonback/newsforyou-visualization:latest

#Add spark master
docker service create --name spark-master --hostname spark-master -p 7077:7077 -p 8080:8080--replicas 1 --network services singularities/spark:latest start-spark master

#Add spark workers
docker service create --name spark-worker --hostname spark-worker --replicas 2 --network services singularities/spark:latest start-spark worker spark-master

#Add spark task submitter (every 1min at the moment)
docker service create --name spark-task-submitter --replicas 1 --network services --restart-delay 1m singularities/spark:latest spark-submit --class org.apache.spark.examples.SparkPi --master spark://spark-master:7077 /usr/local/spark-2.1.0/examples/jars/spark-examples_2.11-2.1.0.jar 10






#!/bin/bash

HOST_IP=192.168.56.1
TOKEN_STORAGE=./docker-token
USER=$(whoami)

#install docker
sudo apt-get -y install \
  apt-transport-https \
  ca-certificates \
  curl

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -

sudo add-apt-repository \
       "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
       $(lsb_release -cs) \
       stable"

sudo apt-get update
sudo apt-get -y install docker-ce


#preparations
sudo mkdir -p /vol
sudo chown $USER /vol
sudo chgrp $USER /vol
mkdir -p /vol/mongo /vol/mongo/data /vol/mongo/config

#join the docker swarm
docker swarm join --token $(cat $TOKEN_STORAGE/worker) $HOST_IP

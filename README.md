# Group-11_Timo-Smit_Timon-Back_Remi-Brandt

This project uses docker swarm for orchestration management.

## Start-up

For the master node:
./swarm.sh

On all other nodes:
./swarm_join.sh
(Change HOST_IP with your master IP, also access to the docker-tokens file has to be granted)


## Data inspection in mongo

docker exec -it scalablecomputing_mongo_1 mongo
use newsForYou
db.users.find()
db.articles.find()
db.ratings.find()

## Compile docker images (images are on hub.docker.com and tryed to keep up to date)
sbt compile (only first time to get the sources)
sbt docker:publishLocal


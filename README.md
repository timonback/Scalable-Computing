# Group-11_Timo-Smit_Timon-Back_Remi-Brandt

## Start-up

curl -L "https://github.com/docker/compose/releases/download/1.11.1/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

docker-compose up -d


## Data inspection in mongo

docker exec -it scalablecomputing_mongo_1 mongo
use newsForYou
db.users.find()
db.articles.find()
db.ratings.find()

## Compile docker images
sbt compile (only first time to get the sources)
sbt docker:publishLocal or sbt docker:publish


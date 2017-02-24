#Run in docker

## Build
sbt docker:publishLocal

## Run
docker run -p 9000:9000 timonback/newsforyou-visualization:1.0

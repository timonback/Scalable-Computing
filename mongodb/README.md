# MongoDB in docker

To run this image, execute the following:

`docker run -dt -p 27017:27017 --name mongodb1 mongodb`

Make sure the container is connected to the `bridge` network. Use `docker inspect mongodbX` (where `X` corresponds to the container #) to do so.


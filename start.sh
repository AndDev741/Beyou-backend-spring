#! /bin/bash

echo "Exporting .env and starting spring"
set -a && source .env && set +a && mvn test && mvn spring-boot:run
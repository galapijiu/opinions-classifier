#!/bin/bash   

nohup java -cp ./target/dependency/*:./target/opinions-tweets-classifier-1.0.jar com.maximgalushka.classifier.twitter.service.MainServiceStart > out.log &
STAT=$?
popd
echo 0

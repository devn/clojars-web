#!/bin/sh

mkdir -p data

rm -f data/dev_db
gunzip -c clojars-test-data.sql.gz | sqlite3 data/dev_db

wget http://clojars.org/stats/all.edn
mv all.edn data/.

wget http://p.hagelb.org/clojars-index.tar.gz
tar -zxvf clojars-index.tar.gz
mv index data/.

wget http://clojars.org/stats/all.edn
mkdir -p data/stats
mv all.edn data/stats/.

#wget https://clojars.org/repo/servlet/servlet/0.1.0-SNAPSHOT/servlet-0.1.0-20101006.015305-5.pom
#wget https://clojars.org/repo/servlet/servlet/0.1.0-SNAPSHOT/maven-metadata.xml
#mkdir -p data/dev_repo/servlet/servlet/0.1.0-SNAPSHOT/
#mv servlet-0.1.0-20101006.015305-5.pom data/dev_repo/servlet/servlet/0.1.0-SNAPSHOT/.
#mv maven-metadata.xml data/dev_repo/servlet/servlet/0.1.0-SNAPSHOT/.

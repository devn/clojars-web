#!/bin/sh

mkdir -p data

# Install dev_db
rm -f data/dev_db
gunzip -c clojars-test-data.sql.gz | sqlite3 data/dev_db

# Run migrations
lein migrate

# Get Lucene index
wget http://p.hagelb.org/clojars-index.tar.gz
tar -zxvf clojars-index.tar.gz
mv index data/.

# Get latest stats file
wget http://clojars.org/stats/all.edn
mkdir -p data/stats
mv all.edn data/stats/.

# Get some poms and metadata for a project
wget https://clojars.org/repo/servlet/servlet/0.1.0-SNAPSHOT/servlet-0.1.0-20101006.015305-5.pom
wget https://clojars.org/repo/servlet/servlet/0.1.0-SNAPSHOT/maven-metadata.xml
mkdir -p data/dev_repo/servlet/servlet/0.1.0-SNAPSHOT/
mv servlet-0.1.0-20101006.015305-5.pom data/dev_repo/servlet/servlet/0.1.0-SNAPSHOT/.
mv maven-metadata.xml data/dev_repo/servlet/servlet/0.1.0-SNAPSHOT/.

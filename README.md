# persister

Quickly prototype your software without having the database slow you down. Simple Persistence for Clojure. Based on/inspired by <https://github.com/SergeyDidenko/Simple-Persistence-for-Clojure/>.

Writing to the FS is useful for development, but for use on platforms like Heroku, the FS isn't available.

## Installation

in project.clj:

	:dependencies [[davidwclin/persister "0.0.1"]]

## Usage

(default) file-based approach:

	(core/init-db)
	
file-based approach with parameters:

	(core/init-db :data-directory "db-loc" 
	              :file-change-interval (* 60 15) 
	              :transaction-chunk-size 1000)
	
db-based using postgres:

	(core/init-db :db-url "postgresql://user:pw@xxx.compute-1.amazonaws.com:5432/dbname")
	
db-based using derby:
	
	(core/init-db :db-url {:classname "org.apache.derby.jdbc.EmbeddedDriver"
	                 	   :subprotocol "derby"
 	                       :subname "db/derby/dbname"
	                       :create true})

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License, the same as Clojure.

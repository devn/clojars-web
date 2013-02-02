(ns clojars.scp
  (:import (java.io InputStream IOException File OutputStream
                    FileOutputStream)
           com.martiansoftware.nailgun.NGContext)
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojars.db :as db]
            [clojars.event :as ev]
            [cemerick.pomegranate.aether :as aether])
  (:gen-class
   :methods [#^{:static true}
             [nailMain [com.martiansoftware.nailgun.NGContext] void]]))

(def max-line-size 4096)
(def max-file-size 20485760)
(def allowed-suffixes #{"pom" "xml" "jar" "sha1" "md5" "asc"})

(defn safe-read-line
  ([#^InputStream stream #^StringBuilder builder]
     (when (> (.length builder) max-line-size)
       (throw (IOException. "Line too long")))

     (let [c (char (.read stream))]
       (if (= c \newline)
         (str builder)
         (do
           (.append builder c)
           (recur stream builder)))))
  ([stream] (safe-read-line stream (StringBuilder.))))

(defn send-okay [#^NGContext ctx]
  (doto (.out ctx)
    (.print "\0")
    (.flush)))

(defn copy-limit
  "Copies at most n bytes from in to out.  Returns the number of bytes
   copied."
  [#^InputStream in #^OutputStream out n]
  (let [buffer (make-array Byte/TYPE 4096)]
    (loop [bytes 0]
      (if (< bytes n)
        (let [size (.read in buffer 0 (min 4096 (- n bytes)))]
          (if (pos? size)
            (do
              (.write out buffer 0 size)
              (recur (+ bytes size)))
            bytes))
        bytes))))

(defn scp-copy [#^NGContext ctx]
  (let [line #^String (safe-read-line (.in ctx))
        [mode size path] (.split line " " 3)
        size (Integer/parseInt size)
        fn (File. #^String path)
        suffix (last (.split (.getName fn) "\\."))]

    (when (> size max-file-size)
      (throw (IOException. (str "Upload too large.  Maximum size is "
                                max-file-size " bytes"))))

    (when-not (allowed-suffixes suffix)
      (throw (IOException. (str "." suffix
                                " files are not supported."))))

    (let [f (File/createTempFile "clojars-upload" (str "." suffix))]
      (.deleteOnExit f)
      (send-okay ctx)
      (with-open [fos (FileOutputStream. f)]
        (let [bytes (copy-limit (.in ctx) fos size)]
          (if (>= bytes size)
            {:name (.getName fn), :file f, :size size, :suffix suffix
             :mode mode}
            (throw (IOException. (str "Upload truncated.  Expected "
                                      size " bytes but got " bytes)))))))))

(defmacro printerr [& strs]
  `(.println (.err ~'ctx) (str ~@(interleave strs (repeat " ")))))

(defn jar-names
  "Construct a few possible name variations a jar might have."
  [jarmap]
  [(str (:name jarmap) "-" (:version jarmap) ".jar")
   (str (:name jarmap) ".jar")])

(defn file-repo [path]
  (.toString (.toURI (File. path))))

(defn artifact-map [jarmap jarfile metafile signatures]
  (let [coords [(symbol (:group jarmap) (:name jarmap)) (:version jarmap)]]
    (merge {(into coords [:extension "jar"]) jarfile
            (into coords [:extension "pom"]) metafile}
           (if-let [jarsig (signatures (str jarfile ".asc"))]
             {(into coords [:extension "jar.asc"]) jarsig})
           (if-let [pomsig (signatures (str metafile ".asc"))]
             {(into coords [:extension "pom.asc"]) pomsig}))))

(defn finish-deploy [#^NGContext ctx, files]
  (let [account (first (.getArgs ctx))
        metadata   (filter #(#{"xml" "pom"} (:suffix %)) files)
        jars       (filter #(#{"jar"}       (:suffix %)) files)
        jarfiles (into {} (map (juxt :name :file) jars))
        signatures (filter #(#{"asc"} (:suffix %)) files)
        signatures (into {} (map (juxt :name :file) signatures))]

    (doseq [metafile metadata
            :when (not= (:name metafile) "maven-metadata.xml")
            :let [jarmap (maven/pom-to-map (:file metafile))
                  names (jar-names jarmap)
                  jarfile (some jarfiles names)
                  artifact-map (artifact-map jarfile metafile signatures)]]
      (when-not jarfile
        (throw (Exception. (str "You need to give me one of: " names))))
      (doseq [file (map :file files)]
        (ev/record-deploy (set/rename-keys jarmap {:name :artifact-id})
                          account file))
      (.println (.err ctx) (str "\nDeploying " (:group jarmap) "/"
                                (:name jarmap) " " (:version jarmap)))
      (db/add-jar account jarmap true)
      (aether/deploy-artifacts :artifacts (keys artifact-map)
                               :files artifact-map
                               :repository [(file-repo (:repo config))]
                               :transfer-listener
                               (bound-fn [e]
                                 (@#'aether/default-listener-fn e)))
      (db/add-jar account jarmap))
    (.println (.err ctx) (str "\nSuccess! Your jars are now available from "
                              "http://clojars.org/"))
    (.flush (.err ctx))))

(defn nail [#^NGContext ctx]
  (try
    (let [in (.in ctx)
          err (.err ctx)
          account (first (.getArgs ctx))]

      (when-not account
        (throw (Exception. "I don't know who you are!")))

      (doto err
        (.println (str "Welcome to Clojars, " account "!"))
        (.flush))

      (loop [files [], okay true]
        (when (> (count files) 100)
          (throw (IOException. "Too many files uploaded at once")))

        (when okay
          (send-okay ctx))

        (let [cmd (.read in)]
          (if (= -1 cmd)
            (finish-deploy ctx files)
            (let [cmd (char cmd)]
              ;; TODO: use core.match
              (condp = cmd
                (char 0)      (recur files false)
                \C            (recur (conj files (scp-copy ctx)) true)
                \D            (do (safe-read-line in) (recur files true))
                \T            (do (safe-read-line in) (recur files true))
                \E            (do (safe-read-line in) (recur files true))
                (throw (IOException. (str "Unknown scp command: '"
                                          (int cmd) "'")))))))))

    (catch Throwable t
      (.println (.err ctx) (str "Error: " (.getMessage t)))
      (.flush (.err ctx))
      (throw t))))

(defn -nailMain [context]
  (nail context))

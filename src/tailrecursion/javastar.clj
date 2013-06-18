(ns tailrecursion.javastar
  (:require
   [alandipert.interpol8 :refer [interpolating]]
   [clojure.core.cache :as cache])
  (:import
   [javax.tools
    DiagnosticCollector
    ForwardingJavaFileManager
    JavaCompiler
    JavaFileObject$Kind
    SimpleJavaFileObject
    StandardJavaFileManager
    ToolProvider]))

(defn source-object
  "Returns a JavaFileObject to store a class file's source."
  [class-name source]
  (proxy [SimpleJavaFileObject]
      [(java.net.URI/create (str "string:///"
                                 (.replace ^String class-name \. \/)
                                 (. JavaFileObject$Kind/SOURCE extension)))
       JavaFileObject$Kind/SOURCE]
    (getCharContent [_] source)))

(defn class-object
  "Returns a JavaFileObject to store a class file's bytecode."
  [class-name baos]
  (proxy [SimpleJavaFileObject]
      [(java.net.URI/create (str "string:///"
                                 (.replace ^String class-name \. \/)
                                 (. JavaFileObject$Kind/CLASS extension)))
       JavaFileObject$Kind/CLASS]
    (openOutputStream [] baos)))

(defn class-manager
  "An in-memory JavaFileManager for storing generated bytecode."
  [manager]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (proxy [ForwardingJavaFileManager] [manager]
      (getClassLoader [location]
        (proxy [clojure.lang.DynamicClassLoader] []
          (findClass [name]
            (let [^clojure.lang.DynamicClassLoader this this]
              (proxy-super defineClass name (.toByteArray baos) nil)))))
      (getJavaFileForOutput [location class-name kind sibling]
        (class-object class-name baos)))))

(defn compile-java
  "Compiles the class-name implemented by source and loads it,
  returning the loaded Class."
  [class-name source]
  (let [compiler (ToolProvider/getSystemJavaCompiler)
        diag (DiagnosticCollector.)
        mgr (class-manager (.getStandardFileManager compiler nil nil nil))
        task (.getTask compiler nil mgr diag nil nil [(source-object class-name source)])]
    (if (.call task)
      (.loadClass (.getClassLoader ^StandardJavaFileManager mgr nil) class-name)
      (throw (RuntimeException.
              (str "java* compilation error: " (first (.getDiagnostics diag)) "\n"
                   "source of generated class: \n" source "\n"))))))

(defn occurrences
  "Count of the occurrences of substring in s."
  ([s substring]
     (occurrences 0 s substring))
  ([n ^String s ^String substring]
     (let [i (.indexOf s substring)]
       (if (neg? i)
         n
         (recur (inc n) (.substring s (inc i)) substring)))))

(defn substitute
  "Replace pattern in s with substitutions."
  [s pattern substitutions]
  (reduce #(.replaceFirst ^String %1 pattern %2) s substitutions))

(defn ^Class generate-class
  "Generates, compiles, and loads a class with a single method, m,
  containing the code string.  Defines m's signature using
  return-type, arg-types, and ~{} occurrences in source as with js*.

  Returns the loaded class's name as a symbol."
  [imports return-type arg-types code]
  (let [class-name (str (gensym "tailrecursion_java_STAR_class"))
        n (occurrences code "~{}")
        arg-names (mapv str (repeatedly n gensym))
        arguments (->> (map #(str %1 " " %2) arg-types arg-names)
                       (interpose \,)
                       (apply str))
        method-body (substitute code "~\\{\\}" arg-names)
        prelude (apply str (map #(format "import %s;" (name %)) imports))
        class-body (interpolating
                    "#{prelude}
                     public class #{class-name} {
                       public static #{return-type} m (#{arguments}) {
                         #{method-body}
                       }
                     }")]
   (compile-java class-name class-body)))

(def prim-strings
  "Type aliases for use with the return-type and arg-types arguments
   of java*."
  '{void     "void"
    boolean  "boolean"
    byte     "byte"
    char     "char"
    float    "float"
    int      "int"
    double   "double"
    long     "long"
    short    "short"
    booleans "boolean []"
    bytes    "byte []"
    chars    "char []"
    floats   "float []"
    ints     "int []"
    doubles  "double []"
    longs    "long []"
    shorts   "short []"})

(def prim-classes
  "Map of primitive aliases to Classes."
  '{boolean  Boolean/TYPE
    byte     Byte/TYPE
    char     Character/TYPE
    float    Float/TYPE
    int      Integer/TYPE
    double   Double/TYPE
    long     Long/TYPE
    short    Short/TYPE
    booleans (Class/forName "[Z")
    bytes    (Class/forName "[B")
    chars    (Class/forName "[C")
    floats   (Class/forName "[F")
    ints     (Class/forName "[I")
    doubles  (Class/forName "[D")
    longs    (Class/forName "[J")
    shorts   (Class/forName "[S")})

(def method-cache (atom (cache/lu-cache-factory {} :threshold 1024)))

(defn ^java.lang.reflect.Method generate-method
  [imports return-type arg-strs arg-classes code]
  (let [k [return-type arg-classes code]]
    (if-let [meth (get @method-cache k)]
      (do (swap! method-cache cache/hit k) meth)
      (let [klass (generate-class imports return-type arg-strs code)
            meth (.getMethod klass "m" (into-array Class arg-classes))]
        (do (swap! method-cache cache/miss k meth) meth)))))

(defmacro java*
  "Similar to ClojureScript's js*.  Compiles a Java code string into a
  Java method and invokes the method with args.

  java* has more arguments than js*.  imports is a vector of
  zero or more fully qualified class names.  return-type and arg-types
  may be either Java classes or symbol aliases for primitive types and
  arrays.  See prim-aliases for available aliases.

  Example:

  (def java-add #(java* [] long [long long] \"return ~{} + ~{};\" %1 %2))
  (java-add 1 2) ;=> 3"
  [imports return-type arg-types code & args]
  {:pre [(= (count arg-types) (count args))]}
  `(let [meth# (generate-method
                (mapv #(.getName ^Class %) ~imports)
                ~(or (prim-strings return-type) `(.getName ^Class ~return-type))
                ~(mapv #(or (prim-strings %) `(.getName ^Class ~%)) arg-types)
                ~(mapv #(or (prim-classes %) %) arg-types)
                ~code)]
     (.invoke meth# nil (into-array Object [~@args]))))

(comment

  (def arr (double-array 1000000 1.0))

  (defn sum1 [a] (reduce + a))

  (dotimes [_ 1000] (time (sum1 arr)))
  ;; (warming up)...
  ;; "Elapsed time: 27.516602 msecs"
  ;; "Elapsed time: 27.67769 msecs"
  ;; "Elapsed time: 30.451273 msecs"

  (defn sum2 [^doubles a]
    (let [len (long (alength a))]
      (loop [sum 0.0
             idx 0]
        (if (< idx len)
          (let [ai (aget a idx)]
            (recur (+ sum ai) (unchecked-inc idx)))
          sum))))

  (dotimes [_ 1000] (time (sum2 arr)))
  ;; (warming up)...
  ;; "Elapsed time: 4.222948 msecs"
  ;; "Elapsed time: 4.23123 msecs"
  ;; "Elapsed time: 4.615039 msecs"

  (defn sum3 [a]
    (java* [] double [doubles]
           "double s = 0;
            double[] arr = ~{};
            for(int i = 0; i < arr.length; i++) {
              s += arr[i];
            }
            return s;" a))

  (dotimes [_ 1000] (time (sum3 arr)))
  ;; (warming up)...
  ;; "Elapsed time: 1.104737 msecs"
  ;; "Elapsed time: 1.097818 msecs"
  ;; "Elapsed time: 1.102997 msecs"

  )

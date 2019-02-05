(ns nand2tetris.vm-to-asm-compiler
  "Nand2Tetris VM code to ASM translator

   Dependencies:
     * Planck, a ClojureScript runtime environment based on JavaScriptCore and 
               the self hosted version of ClojureScript.
   
   Running:
     * `planck ./vm-to-asm-compiler.cljs dir1 dir2 ...`

   General algorithm:
     Input: a list of directories that may contain .vm code files.. 
     Output: .asm files for each .vm file. The .asm files are placed in the
             same directory as it's corresponding .vm file.
     Algorithm:
        - recursively search the given directories for .vm files
        - compile each .vm file line by line according to the hack computer and 
          vm specs into hack assembly code.
        - Write the code for each file into a .asm file.


   Reference Information for the Hack machine and the VM language.
  
   memory segments
   -------------------------------------
     argument - R[2], ARG 
     local    - R[1], LCL
     static   - with assembler symbols 
     constant - offset is the constant 
     this     - R[3], THIS
     that     - R[4], THAT
     pointer  - R[3], R[4]
     temp     - R[5-12]
  
   ops
   -------------------------------------
     add
     sub
     neg
     eq
     gt
     lt
     and
     or
     not
     push <mem-seg> <offset>
     pop <mem-seg> <offset>
     function
     call
     return
     label
     goto
     if-goto
  
   memory space
   -------------------------------------
     R[0]            SP
     R[1]            LCL
     R[2]            ARG
     R[3]            THIS
     R[4]            THAT
     R[5-12]         temp segment
     R[13-15]        general purpose registers for VM
     R[16-255]       static / assembler labels
     R[256-2047]     stack
     R[2048-16383]   heap
     R[16384-24575]  memory mappe I/O
     R[24575-32767]  unused memory space?"
  (:require [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [planck.core :as p]
            [planck.io :as io]))


(def dec-sp
  ^{:doc "Snippet to decrement the stack pointer."}
  ["@SP"
   "M=M-1"])


(def inc-sp
  ^{:doc "Snippet to increment the stack pointer."}
  ["@SP"
   "M=M+1"])


(def pop-to-D
  ^{:doc "Snippet to pop the a single value from the stack and store it in D"}
  [dec-sp
   "A=M"
   "D=M"])


(def push-D
  ^{:doc "Snippet to push the value of D onto the stack."} 
  ["@SP"
   "A=M"
   "M=D"
   inc-sp])


(defmulti compile-cmd
  "Translates a single command to assembly code. Returns a potentially nested
  vector of strings. Using clojure.core/flatten will result in a flat sequence
  of strings in the correct order."
  (fn [file [cmd mem-seg offset]]
    (keyword cmd)))


(defn throw-invalid-seg
  "Helper fn that throws an error stating that an invalid memory segment was 
  referenced in the program."
  [mem-seg]
  (throw (js/Error. (str mem-seg " is not a valid memory segment."))))


(defn static-addr
  "Given a planck.io/File name and segment index returns the A instruction of
  the form @filename.offset"
  [file offset]
  (let [sym (->> (str file)
                 (re-seq #"([^/]*)\.vm$") 
                 first
                 second)]
    (str "@" sym "." offset)))


(def relative-segs
  ^{:doc "Maps relative memory segment names to the corresponding assembler 
         symbols"}
  {"local" "LCL"
   "argument" "ARG"
   "this" "THIS"
   "that" "THAT"})


(def absolute-segs
  ^{:doc "Maps static memory segment names to a vector of the form:
         [base-address max-offset]"}
  {"temp" [5 7] 
   "pointer" [3 1]})


(defn mem-seg-addr-to-A
  "Given a relative memory segment (local, argument, this, that) and an index
  returns a vector of assembly code that sets the A register to the referenced
  address."
  [mem-seg offset] 
  (assert (contains? relative-segs mem-seg)
          (str "Valid memory segments are " (keys relative-segs)))
  [(str "@" offset)
   "D=A"
   (str "@" (relative-segs mem-seg))
   "A=M"
   "A=D+A"])


(defn static-mem-seg-addr
  "Given a static memory segment and an offset returns the Rxx symbol 
  corresponding to the location."
  [mem-seg offset]
  (let [[base max-offset] (absolute-segs mem-seg)]
    (assert (<= 0 offset max-offset)
            (str "Index out of range for " mem-seg " segment."
                 "Range is 0 <= " offset " <= " max-offset))
    (str "R" (+ base offset))))


(defn push-absolute-seg
  "Generates assembly code that pushes a value from an absolute segment 
  (pointer, temp) to the stack."
  [mem-seg offset]
  [(str "// push " mem-seg)
   (str "@" (static-mem-seg-addr mem-seg offset))
   "D=M"
   push-D
   (str "// end push " mem-seg)])


(defn push-constant-seg
  "Generates assembly code that pushes a constant value onto the stack."
  [value]
  ["// push constant"
   (str "@" value)
   "D=A"
   push-D
   "// end push constant"])


(defn push-static-seg
  "Generates assembly code that pushes a static variable onto the stack."
  [file offset]
  ["// push static"
   (static-addr file offset)
   "D=M"
   push-D
   "// end push static"])


(defn push-relative-seg
  "Generates assembly code that pushes a value from a relative segment (local,
  argument, this, that) onto the stack."
  [mem-seg offset]
  [(str "// push " mem-seg)
   (mem-seg-addr-to-A mem-seg offset)
   "D=M"
   push-D
   (str "// end push " mem-seg)])


; Generates assembly code for the push instruction. Throws an error if an 
; invalid memory segment is referenced.
(defmethod compile-cmd :push
  [file [_ mem-seg offset]]
  (let [offset (js/parseInt offset)]
    (cond (= mem-seg "constant") (push-constant-seg offset)
          (= mem-seg "static") (push-static-seg file offset)
          (contains? relative-segs mem-seg) (push-relative-seg mem-seg offset)
          (contains? absolute-segs mem-seg) (push-absolute-seg mem-seg offset)
          :else (throw-invalid-seg mem-seg))))


(defn pop-static-seg
  "Generates assembly code that pops the top value from the stack into a stack
  variable."
  [file offset]
  ["// pop static"
   pop-to-D
   (static-addr file offset)
   "M=D"
   "// end pop static"])


(defn pop-relative-seg
  "Generates assembly code that pops the top value from the stack into a 
  relative memory segment (local, argument, this, that)."
  [mem-seg offset]
  [(str "// pop " mem-seg)
   (mem-seg-addr-to-A mem-seg offset)
   "D=A"
   "@R13"
   "M=D"
   pop-to-D
   "@R13"
   "A=M"
   "M=D"
   (str "// end pop " mem-seg)])


(defn pop-absolute-seg
  "Generates assembly code that pops the top value from the stack into an 
  absolute memory segment (pointer, temp)."
  [mem-seg offset]
  [(str "// pop " mem-seg)
   pop-to-D
   (str "@" (static-mem-seg-addr mem-seg offset))
   "M=D"
   (str "// end pop " mem-seg)])


; Generates assembly code for the pop instruction. Throws an error if an invalid
; memory segment is referenced.
(defmethod compile-cmd :pop
  [file [_ mem-seg offset]]
  (let [offset (js/parseInt offset)]
    (cond (= mem-seg "static") (pop-static-seg file offset)
          (contains? relative-segs mem-seg) (pop-relative-seg mem-seg offset)
          (contains? absolute-segs mem-seg) (pop-absolute-seg mem-seg offset)
          :else (throw-invalid-seg mem-seg))))


(defn gen-binary-op 
  "Generates assembly code to perform a binary operation on the stack. 
  Ordering is <second to top item on stack> <op> <top item on stack>"
  [op]
  [(str "// start binary op, " op)
   ; 2nd thing on stack -> D
   "@SP"
   "M=M-1"
   "M=M-1"
   "A=M"
   "D=M"
   ; 1st thing on stack -> M
   "@SP"
   "M=M+1"
   "A=M"
   ; do operation
   (str "D=D" op "M")
   ; put D onto stack, can't use push-D because @SP was messed with before
   "@SP"
   "M=M-1"
   "A=M"
   "M=D"
   ; move SP
   inc-sp
   (str "// end binary op, " op)])


(defn gen-unary-op
  "Generates assembly code to perform a unary operation on the stack."
  [op]
  [(str "// start unary op, " op)
   "@SP"
   "A=M"
   "A=A-1"
   (str "M=" op "M")
   (str "// end unary op, " op)])


(defn label
  "Generates an assembly label."
  [sym]
  (str "(" sym ")"))


(defn gen-cond-op
  "Generates assembly code to execute a comparison operator on the stack.  
  Ordering is the same as gen-binary-op. Uses the various jump instructions
  to conditionally push true = -1, and false = 0 onto the stack."
  [op]
  (let [; generate two unique labels for the true case and the false case
        tl (gensym "COND_TRUE_")
        el (gensym "COND_FALSE_")]
    ["// start conditional eval"
     ; do subtraction to use jumps to compare against 0
     ; Example: x < y  ->  x - y < 0
     (gen-binary-op "-")
     pop-to-D
     ; jump to true label if operation result is true, otherwise continue
     (str "@" tl)
     (str "D;J" op) 
     ; jump did not happen so D=false, jump to el 
     "D=0"
     (str "@" el)
     "0;JMP"
     (label tl)
     ; jump did happen set D=true
     "D=-1"
     (label el)
     push-D
     "// end conditional eval"])) 


; Implementations for all the arithmetic and logical operators.
(defmethod compile-cmd :add
  [_ _]
  (gen-binary-op "+"))

(defmethod compile-cmd :sub
  [_ _]
  (gen-binary-op "-"))

(defmethod compile-cmd :and
  [_ _]
  (gen-binary-op "&"))

(defmethod compile-cmd :or
  [_ _]
  (gen-binary-op "|"))

(defmethod compile-cmd :lt
  [_ _]
  (gen-cond-op "LT"))

(defmethod compile-cmd :gt
  [_ _]
  (gen-cond-op "GT"))

(defmethod compile-cmd :eq
  [_ _]
  (gen-cond-op "EQ"))

(defmethod compile-cmd :neg
  [_ _]
  (gen-unary-op "-"))

(defmethod compile-cmd :not
  [_ _]
  (gen-unary-op "!"))


(defn compile-vm-code
  "Given a file path and the code in that file compiles and returns a vector
  of assembly code instructions. The vector may contain nested vectors."
  [file code]
  (into []
        (comp 
          ; remove comments and blank lines
          (map #(string/replace % #"//.*$" ""))
          (map string/trim) 
          (remove empty?)
          ; split instruction arguments
          (map #(string/split % #"\s+"))
          ; compile each instruction
          (mapcat (partial compile-cmd file)))
        ; for each line in the code...
        (string/split-lines code)))


(def vm-file-pattern #"\.vm$")


(defn vm-file?
  "Does the given file have a .vm extension."
  [file]
  (re-find vm-file-pattern (str file)))


(defn output-file
  "Given a .vm file path generates it's corresponding .asm file path."
  [file]
  (.replace (str file) vm-file-pattern ".asm"))


(defn file-seq
  "Implementation of JVM Clojure's file-seq with planck.io."
  [dir]
  (tree-seq (fn [file] (io/directory? file))
            (fn [dir] (io/list-files dir))
            dir))


(defn collect-vm-files
  "Given a collection of directories returns a vector of .vm files in those 
  directories."
  [dirs]
  (into [] (comp (mapcat file-seq)
                 (filter vm-file?))
        dirs))


(defn prelude-val
  "Generates assembly code to set up a single value for the prelude, see below."
  [pointer v]
  [(str  "// set " pointer)
   (str "@" v)
   "D=A"
   (str "@" pointer)
   "M=D"])


(def prelude
  ^{:doc "Assembly code that sets up the initial state of the computer."}
  ["// prelude"
   (prelude-val "SP" 256)
   (prelude-val "LCL" 300)
   (prelude-val "ARG" 400)
   (prelude-val "THIS" 3000)
   (prelude-val "THAT" 3010)
   "// end prelude"])


(defn compile!
  [dirs]
  (doseq [file (collect-vm-files dirs)]
    (println "Compiling " (str file))
    (->> 
      ; read each file and compile it into a vector of assembly code
      (p/slurp file)
      (compile-vm-code file)
      ; prepend the prelude
      (into prelude)
      ; take the nested vector of vectors of assembly code and flatten it into
      ; a single sequence of instructions
      (flatten)
      ; join the sequence of instructions into a single string and write the file.
      (string/join "\n")
      (p/spit (output-file file)))))


(defn validate-args! 
  "Asserts that every item in args is a directory."
  [args]
  (doseq [f args]
    (assert (io/directory? (io/as-file f))
            "All arguments must be directories.")))


(defn -main
  [& args]
  (validate-args! args)
  (compile! args))


(set! *main-cli-fn* -main)


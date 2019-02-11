(ns nand2tetris.vm-to-asm-compiler
  "Nand2Tetris VM code to ASM translator

   Dependencies:
     * Planck, a ClojureScript runtime environment based on JavaScriptCore and 
               the self hosted version of ClojureScript.
   
   Running:
     * `planck ./vm-to-asm-compiler.cljs dir1` or:
     * `planck ./vm-to-asm-compiler.cljs a/b/c.vm`

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
     R[16384-24575]  memory mapped I/O
     R[24575-32767]  unused memory space?"
  (:require [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as spec]
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


(defn with-comment 
  [c code]
  [(str "// start " (prn-str c))
   code
   (str "// end " (prn-str c))])


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
  [(str "@" (static-mem-seg-addr mem-seg offset))
   "D=M"
   push-D])


(defn push-constant-seg
  "Generates assembly code that pushes a constant value onto the stack."
  [value]
  [(str "@" value)
   "D=A"
   push-D])


(defn push-static-seg
  "Generates assembly code that pushes a static variable onto the stack."
  [file offset]
  [(static-addr file offset)
   "D=M"
   push-D])


(defn push-relative-seg
  "Generates assembly code that pushes a value from a relative segment (local,
  argument, this, that) onto the stack."
  [mem-seg offset]
  [(mem-seg-addr-to-A mem-seg offset)
   "D=M"
   push-D])


; Generates assembly code for the push instruction. Throws an error if an 
; invalid memory segment is referenced.
(defmethod compile-cmd :push
  [file [_ mem-seg offset :as cmd]]
  (with-comment cmd
    (let [offset (js/parseInt offset)]
      (cond (= mem-seg "constant") (push-constant-seg offset)
            (= mem-seg "static") (push-static-seg file offset)
            (contains? relative-segs mem-seg) (push-relative-seg mem-seg offset)
            (contains? absolute-segs mem-seg) (push-absolute-seg mem-seg offset)
            :else (throw-invalid-seg mem-seg)))))


(defn pop-static-seg
  "Generates assembly code that pops the top value from the stack into a stack
  variable."
  [file offset]
  [pop-to-D
   (static-addr file offset)
   "M=D"])


(defn pop-relative-seg
  "Generates assembly code that pops the top value from the stack into a 
  relative memory segment (local, argument, this, that)."
  [mem-seg offset]
  [(mem-seg-addr-to-A mem-seg offset)
   "D=A"
   "@R13"
   "M=D"
   pop-to-D
   "@R13"
   "A=M"
   "M=D"])


(defn pop-absolute-seg
  "Generates assembly code that pops the top value from the stack into an 
  absolute memory segment (pointer, temp)."
  [mem-seg offset]
  [pop-to-D
   (str "@" (static-mem-seg-addr mem-seg offset))
   "M=D"])


; Generates assembly code for the pop instruction. Throws an error if an invalid
; memory segment is referenced.
(defmethod compile-cmd :pop
  [file [_ mem-seg offset :as cmd]]
  (with-comment cmd
    (let [offset (js/parseInt offset)]
      (cond (= mem-seg "static") (pop-static-seg file offset)
            (contains? relative-segs mem-seg) (pop-relative-seg mem-seg offset)
            (contains? absolute-segs mem-seg) (pop-absolute-seg mem-seg offset)
            :else (throw-invalid-seg mem-seg)))))


(defn gen-binary-op 
  "Generates assembly code to perform a binary operation on the stack. 
  Ordering is <second to top item on stack> <op> <top item on stack>"
  [op]
  (with-comment (str "binary op, " op)
    [; 2nd thing on stack -> D
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
     inc-sp]))


(defn gen-unary-op
  "Generates assembly code to perform a unary operation on the stack."
  [op]
  (with-comment (str "Unary op, " op)
    ["@SP"
     "A=M"
     "A=A-1"
     (str "M=" op "M")]))


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
    (with-comment (str "Conditional op, " op)
      [; do subtraction to use jumps to compare against 0
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
       push-D])))


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



; impl for function calls / returns


(defmethod compile-cmd :function
  [_ [_ fn-name local-count :as cmd]]
  (with-comment cmd
    [; create a label for the function
     (label fn-name)
     ; initialize local variables to 0
     (into [] (repeat local-count (push-constant-seg 0)))]))


(defn psuedo-pop-frame-item 
  "Used by :return command to 'pop' a value from FRAME/R13 to dest."
  [dest]
  ["@R13"
   "M=M-1"
   "A=M"
   "D=M"
   (str "@" dest)
   "M=D"])


(defmethod compile-cmd :return
  [_ cmd]
  (with-comment cmd
    ["// FRAME = LCL"
     "@LCL"
     "D=M"
     "@R13"
     "M=D"
     "// RET = *(FRAME - 5)"
     "@R13"
     "D=M"
     "@5"
     "D=D-A"
     "A=D"
     "D=M"
     "@R14"
     "M=D"
     "// *ARG = pop()"
     pop-to-D
     "@ARG"
     "A=M"
     "M=D"
     "// SP = ARG + 1"
     "@ARG"
     "D=M"
     "D=D+1"
     "@SP"
     "M=D"
     "// THAT = *(FRAME - 1)"
     (psuedo-pop-frame-item "THAT")
     "// THIS = *(FRAME - 2)"
     (psuedo-pop-frame-item "THIS")
     "// ARG = *(FRAME - 3)"
     (psuedo-pop-frame-item "ARG")
     "// LCL = *(FRAME - 4)"
     (psuedo-pop-frame-item "LCL")
     "// goto RET"
     "@R14"
     "A=M"
     "0;JMP"]))

(defmethod compile-cmd :call
  [_ [_ fn-name arg-count :as cmd]]
  (with-comment cmd
    (let [return-label (gensym (str "RETURN_FROM_" fn-name))]
      ["// save the dynamic segment locations"
       (push-constant-seg return-label)
       (push-constant-seg "LCL")
       (push-constant-seg "ARG")
       (push-constant-seg "THIS")
       (push-constant-seg "THAT")
       "// ARG = SP - arg-count - 5"
       "@SP"
       "D=M"
       (str "@" arg-count)
       "D=D-A"
       "@5"
       "D=D-A"
       "@ARG"
       "M=D"
       "// LCL = SP"
       "@SP"
       "D=A"
       "@LCL"
       "M=D"
       (str "// jump to " fn-name)
       (str "@" fn-name)
       (str "0;JMP")
       (label return-label)])))


; impl for control flow instructions

(defmethod compile-cmd :label
  [_ [_ label-name :as cmd]]
  (with-comment cmd
    [(label label-name)]))

(defmethod compile-cmd :goto
  [_ [_ label-name :as cmd]]
  (with-comment cmd
    [(str "@" label-name)
     "0;JMP"]))

(defmethod compile-cmd :if-goto
  [_ [_ label-name :as cmd]]
  (with-comment cmd
    [pop-to-D
     (str "@" label-name)
     "D;JNE"]))


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


(defn program->str
  [program]
  (->> program (flatten) (string/join "\n")))


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
  (program->str 
    (with-comment "PRELUDE"
      [(prelude-val "SP" 256)
       (prelude-val "LCL" 300)
       (prelude-val "ARG" 400)
       (prelude-val "THIS" 3000)
       (prelude-val "THAT" 3010)])))


(def sys-init-call
  (let [end-loop (gensym "END_LOOP_")]
    (program->str
      (with-comment "Bootstrap Sys.init call"
        [(compile-cmd nil [:call "Sys.init" 0])
         (label end-loop)
         (str "@" end-loop)
         "0;JMP"]))))


(defn build-options
  [options]
  (str (if (contains? options "--no-prelude") "" prelude)
       (if (contains? options "--no-sys-init") "" sys-init-call)))


(defn compile-file*
  "Compiles a single vm file and returns a string of the asm code."
  [file]
  (println "Compiling" (str file))
  (->> (p/slurp file)
       (compile-vm-code file)
       (program->str)))


(defn compile-file
  "Compiles a single vm file. The output asm file will be a sibling of the vm 
  file on the filesystem."
  [file options]
  (p/spit (output-file file)
          (compile-file* file)))


(defn dir->asm-file
  "Given a directory to be compiled generates the name of the output asm file."
  [dir]
  (let [path (string/replace (str dir) #"/$" "")
        [_ filename] (re-find #"/([^/]+)$" path)]
    (io/file path (str filename ".asm"))))


(defn compile-dir
  "Compiles all the vm files in the given directory. Outputs a single asm file
  in the pwd with the same name as the directory."
  [dir options]
  (let [asm-file (dir->asm-file dir)]
    (p/spit asm-file (build-options options))
    (p/spit asm-file (str "// Compiled from dir " dir "\n") :append true)
    (doseq [file (io/list-files dir)]
      (when (vm-file? file)
        (p/spit asm-file 
                (str "// Start " file "\n" 
                     (compile-file* file) "\n"
                     "// End " file "\n")
                :append true)))))


(def usage "Usage: planck vm-to-asm-compiler.cljs <source> <options>\nsource is a directory or an vm file.\nOptions: --no-sys-init and --no-prelude")

(spec/def ::args 
  (spec/cat :res string? 
            :options (spec/* #{"--no-sys-init" "--no-prelude"})))

(defn compile-res
  [res options]
  (let [file (io/file res)]
    (if (io/directory? file)
      (compile-dir file options)
      (compile-file file options))))

(defn -main
  [& args]
  (let [{:keys [res options] :as v} (spec/conform ::args args)]
    (if (= v ::spec/invalid)
      (println usage)
      (compile-res res (into #{} options)))))


(set! *main-cli-fn* -main)


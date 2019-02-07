(ns assembler
  (:require [clojure.string :as string]
            [planck.core :as p]
            [planck.io :as io]))


(def base-symbol-table 
  ^{:doc "The initial symbol table, updated during the first pass."}
  {"SP" 0x0000
   "LCL" 0x0001
   "ARG" 0x0002
   "THIS" 0x0003
   "THAT" 0x0004
   "R0" 0x0000
   "R1" 0x0001
   "R2" 0x0002
   "R3" 0x0003
   "R4" 0x0004
   "R5" 0x0005
   "R6" 0x0006
   "R7" 0x0007
   "R8" 0x0008
   "R9" 0x0009
   "R10" 0x000a
   "R11" 0x000b
   "R12" 0x000c
   "R13" 0x000d
   "R14" 0x000e
   "R15" 0x000f
   "SCREEN" 0x4000
   "KBD" 0x6000})


(def dest-bits
  ^{:doc "Mapping from dest instructions to corresponding bit patterns."}
  {nil "000"
   "null" "000"
   "M" "001"
   "D" "010"
   "MD" "011"
   "A" "100"
   "AM" "101"
   "AD" "110"
   "AMD" "111"})


(def comp-bits
  ^{:doc "Mapping from computation instructions to corresponding bit patterns."}
  {"0" "0101010"
   "1" "0111111"
   "-1" "0111010"
   "D" "0001100"
   "A" "0110000"
   "!D" "0001101"
   "!A" "0110001"
   "-D" "0001111"
   "-A" "0110011"
   "D+1" "0011111"
   "A+1" "0110111"
   "D-1" "0001110"
   "A-1" "0110010"
   "D+A" "0000010"
   "D-A" "0010011"
   "A-D" "0000111"
   "D&A" "0000000"
   "D|A" "0010101"
   "M" "1110000"
   "!M" "1110001"
   "-M" "1110011"
   "M+1" "1110111"
   "M-1" "1110010"
   "D+M" "1000010"
   "D-M" "1010011"
   "M-D" "1000111"
   "D&M" "1000000"
   "D|M" "1010101"})


(def jump-bits
  ^{:doc "Mapping from jump instructions to corresponding bit patterns."}
  {nil "000"
   "null" "000"
   "JGT" "001"
   "JEQ" "010"
   "JGE" "011"
   "JLT" "100"
   "JNE" "101"
   "JLE" "110"
   "JMP" "111"})


(def label-pattern #"\(([\w\.\_\$]+)\)")

(def a-instr-pattern #"@([\w\.\_\$]+)")

(def c-instr-pattern #"(([A-Z]+)=)?([0-9A-Z\+\-\|\&\!]+)(;([A-Z]+))?")

(def comment-pattern #"//.*$")


(defn correct-c-instr-combo?
  "Determines if the right combinations of C instructions parts are present."
  [dest cmp jump]
  (or (and (some? dest) (some? cmp) (nil? jump))
      (and (nil? dest) (some? cmp) (some? jump))
      (and (some? dest) (some? cmp) (some? jump))))

(defn defined-c-instr?
  "Determines if the C instruction parts actually correspond to assembly 
  instructions."
  [dest cmp jump]
  (and (contains? dest-bits dest)
       (contains? comp-bits cmp)
       (contains? jump-bits jump)))


(defn parse-c-instr
  "Given a line containing a C instruction parses it into an instruction 
  vector."
  [line]
  (let [[whole _ dest cmp _ jump] (re-find c-instr-pattern line)]
    (if (and (correct-c-instr-combo? dest cmp jump) 
             (defined-c-instr? dest cmp jump))
      [:itype/c dest cmp jump])))


(defn parse-a-instr
  "Determines with kind of value an A instruction has and returns the correct
  instruction vector."
  [sym]
  (let [addr (js/parseInt sym)]
    (if (js/isNaN addr) 
      [:itype/a :vtype/sym sym]
      [:itype/a :vtype/addr addr])))


(defn parse-line
  "Given a line containing an A instruction parsed it into an instruction 
  vector."
  [line]
  (let [a-instr-match (re-find a-instr-pattern line)
        c-instr (parse-c-instr line)
        label-match (re-find label-pattern line)]
    (cond (some? a-instr-match) (parse-a-instr (second a-instr-match))
          (some? c-instr) c-instr 
          (some? label-match) [:itype/label (second label-match)]
          :else (throw (js/Error. (str "Invalid instruction: " line))))))


(defn replace-comment
  "Given a line removes comments from it."
  [line]
  (string/replace line comment-pattern ""))


(def first-pass-xform
  ^{:doc "Transducer for the first pass."}
  (comp (map string/trim)
        (map replace-comment)
        (remove empty?)
        (map parse-line)))


(defn append-instr
  "During the first pass appends an instruction to the :program key in the 
  result map and increments the ROM address."
  [result instr]
  (-> result 
      (update :program conj instr)
      (update :rom-addr inc)))


(defn add-label 
  "During the first pass appends a label to the symbol table with the current
  ROM address."
  [{:keys [sym-tab rom-addr] :as result} [_ label]]
  (if (contains? sym-tab label)
    (throw (js/Error. (str "Duplicate label: " label)))
    (update result :sym-tab assoc label rom-addr)))


(defn first-pass-reducer
  "The reducing function for the first pass. Labels are added to the symbol
  table without incrementing the ROM address. Other instructions are appended
  to the program and the ROM address is incremented."
  [result [itype :as instr ]]
  (if (= :itype/label itype)
    (add-label result instr)
    (append-instr result instr)))


(def first-pass-initial-state
  ^{:doc "The initial state for the accumulator in the first pass reduction."}
  {:program []
   :sym-tab base-symbol-table
   :rom-addr 0})


(defn first-pass
  "Given raw lines from an assembly file executes the first pass. This function
  returns a map with with keys:
      :program  - vector of instruction vectors ready for the second pass.
      :sym-tab  - the symbol table with all base symbols and labels
      :rom-addr - the address just after the last ROM address in the program."
  [lines]
  (transduce first-pass-xform 
             (completing first-pass-reducer) 
             first-pass-initial-state
             lines))


(defmulti gen-machine-instr
  "Reducer function for the second pass. Appends the binary string instructions
  to the :program key in the accumulator. May update the symbol table when A
  instruction variables are encountered."
  (fn [result [itype & args]] itype))


(defn number->bin-str
  "Given a string or number converts it to a 15 bit binary string."
  [x]
  (-> (unsigned-bit-shift-right (js/parseInt x) 0)
      (.toString 2)
      (.padStart 15 "0")))


(defn add-bin-instr
  "Adds the given binary instruction to the program vector in the second pass
  accumulator."
  [result bin]
  (update result :program conj bin))


(defmethod gen-machine-instr :itype/a
  [{:keys [sym-tab var-addr] :as result} [itype vtype value]]
  (if (= vtype :vtype/addr)
    ; if the A instruction is an address, just translate to binary and append 
    ; to the :program vector. 
    (add-bin-instr result (str "0" (number->bin-str value)))
    ; Handle the case where the A instruction is a symbol
    (if-let [addr (get sym-tab value)]
      ; the symbol is in the symbol table, create a new A instruction vector
      ; and recur back to this method to hit the first case.
      (recur result [itype :vtype/addr addr])
      ; the symbol is *not* in the symbol table so it's a variable that needs
      ; to be allocated. The next available address is in the :var-addr 
      ; accumulator slot. Update the symbol table with the new symbol and new 
      ; variable address. Increment the :var-addr. Create a new A instruction
      ; with the new address and recur back to this method.
      (recur (-> (update result :var-addr inc)
                 (update :sym-tab assoc value var-addr))
             [itype :vtype/addr var-addr]))))


(defmethod gen-machine-instr :itype/c
  [result [_ dest cmp jump]]
  ; Easier than the A instructions, just a simple look up and string concat
  (add-bin-instr result
                 (str "111"
                      (comp-bits cmp)
                      (dest-bits dest)
                      (jump-bits jump))))


(def ^{:doc "The address at which assembly code variables are stored"}
  var-start-addr 0x0010)

(defn second-pass-init
  "The initial accumulator for the second pass reduction."
  [sym-tab]
  {:program [] 
   :var-addr var-start-addr
   :sym-tab sym-tab})

(defn second-pass
  "The second pass reduction."
  [{:keys [program sym-tab]}]
  (reduce gen-machine-instr 
          (second-pass-init sym-tab)
          program))


(defn compile-program
  "Given a sequence of lines returns the corresponding sequence of binary 
  hack machine instructions."
  [lines]
  (-> lines
      first-pass
      second-pass
      :program))


(defn hack-file
  "Given a path to an asm file returns a new path with the .asm extension 
  replaced with .hack"
  [asm-file]
  (io/file (string/replace asm-file #"asm$" "hack")))


(defn compile-file!
  "Complies the given asm file and writes it's corresponding hack file."
  [asm-file]
  (->> (p/slurp asm-file)
       (string/split-lines)
       (compile-program)
       (string/join "\n")
       (p/spit (hack-file asm-file))))


(defn -main
  "The main function. Arguments can be 0 or more paths to asm files."
  [& args]
  (doseq [f args]
    (compile-file! f)))


(set! *main-cli-fn* -main)

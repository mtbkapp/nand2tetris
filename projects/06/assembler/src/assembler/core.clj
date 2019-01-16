(ns assembler.core
  (:require [instaparse.core :as insta]
            [clojure.string :as string]
            [clojure.test :refer :all])
  (:import instaparse.gll.Failure)
  (:gen-class))


(def grammar
  "
  <line> = <whitespace>? (A | C | label)? <whitespace>? <comment>? <whitespace>?

  comment = '//' #'.*'
  
  A = <'@'> (number | symbol) <whitespace>
  symbol = #'[A-z][A-z0-9.$_-]*'
  number = '0' | #'[1-9][0-9]*'

  C = dest? comp jump?

  dest = ('null' | 'M' | 'D' | 'MD' | 'A' | 'AM' | 'AD' | 'AMD') <'='>
  comp = comp_a0 | comp_a1
  comp_a0 = ('0' | '1' | '-1' | 'D' | 'A' | '!D' | '!A' | '-D' | '-A' | 'D+1' | 'A+1' | 'D-1' | 'A-1' | 'D+A' | 'D-A' | 'A-D' | 'D&A' | 'D|A')
  comp_a1 = ('M' | '!M' | 'M+1' | 'M-1' | 'D+M' | 'D-M' | 'M-D' | 'D&M' | 'D|M')
  jump = <';'> ('null' | 'JGT' | 'JEQ' | 'JGE' | 'JLT' | 'JNE' | 'JLE' | 'JMP')

  label = <'('> symbol <')'>

  whitespace = #'\\s*'
  ")


(def parse-line (insta/parser grammar))


(defn parse
  [program]
  (into []
        (comp (map parse-line)
              (map #(if (instance? instaparse.gll.Failure %)
                      (throw (ex-info "Parse Error" %))
                      %))
              (map first)
              (remove empty?))
        (string/split-lines program)))


(def base-symbol-table 
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


(def jump-bits
  {nil "000"
   "null" "000"
   "JGT" "001"
   "JEQ" "010"
   "JGE" "011"
   "JLT" "100"
   "JNE" "101"
   "JLE" "110"
   "JMP" "111"})


(def dest-bits
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
  {0 {"0" "101010"
      "1" "111111"
      "-1" "111010"
      "D" "001100"
      "A" "110000"
      "!D" "001101"
      "!A" "110001"
      "-D" "001111"
      "-A" "110011"
      "D+1" "011111"
      "A+1" "110111"
      "D-1" "001110"
      "A-1" "110010"
      "D+A" "000010"
      "D-A" "010011"
      "A-D" "000111"
      "D&A" "000000"
      "D|A" "010101"}
   1 {"M" "110000"
      "!M" "110001"
      "-M" "110011"
      "M+1" "110111"
      "M-1" "110010"
      "D+M" "000010"
      "D-M" "010011"
      "M-D" "000111"
      "D&M" "000000"
      "D|M" "010101"}})



(defn label-locations
  [parsed]
  (loop [rom-addr -1 
         table {}
         [p & ps] parsed]
    (if (some? p)
      (if (= :label (first p))
        (let [label (-> p second second)
              addr (inc rom-addr)]
          (recur rom-addr (assoc table label addr)  ps))
        (recur (inc rom-addr) table ps))
      table)))


(defn a-instr
  [addr]
  (as-> addr n
    (min n 0x7fff)
    (Integer/toBinaryString n)
    (format "%15s" n)
    (str "0" n)
    (string/replace n " " "0")))


(defn assemble-a
  [[_ [arg-type arg] :as p] sym-tab var-addr]
  (if (= :symbol arg-type)
    (if (contains? sym-tab arg)
      [sym-tab var-addr (a-instr (get sym-tab arg))]
      [(assoc sym-tab arg var-addr) (inc var-addr) (a-instr var-addr)])
    [sym-tab var-addr (a-instr (Long/valueOf arg))]))


(defn assemble-c
  [instr]
  (let [{d :dest [compa c] :comp j :jump} (into {} (rest instr))
        a (if (= :comp_a1 compa) 1 0)]
    (str "111"
         a
         (get-in comp-bits [a c] "000000")
         (get dest-bits d)
         (get jump-bits j))))


(defn assemble*
  [sym-tab var-addr code [[itype :as p] & ps]]
  (if (some? p)
    (cond (= :C itype) (recur sym-tab var-addr (conj code (assemble-c p)) ps)
          (= :A itype) (let [[sym-tab' var-addr' instr] (assemble-a p sym-tab var-addr)]
                         (recur sym-tab' var-addr' (conj code instr) ps))
          :else (recur sym-tab var-addr code ps))
    code))


(def var-start 0x0010)


(defn assemble
  [program]
  (let [parsed (parse program)
        labels (label-locations parsed)]
    (assemble* (merge base-symbol-table labels) var-start [] parsed)))


(defn write-code
  [code file]
  (spit file (string/join "\n" code)))


; Dev / Testing ----------------------------------------------------------------

(def maxL-code "@0
               D=M
               @1
               D=D-M
               @10
               D;JGT
               @1
               D=M
               @12
               0;JMP
               @0
               D=M
               @2
               M=D
               @14
               0;JMP")


(def rectL-code "@0
                D=M
                @23
                D;JLE
                @16
                M=D
                @16384
                D=A
                @17
                M=D
                @17
                A=M
                M=-1
                @17
                D=M
                @32
                D=D+A
                @17
                M=D
                @16
                MD=M-1
                @10
                D;JGT
                @23
                0;JMP")


(defn test-assemble
  [code symbol-file expected-file]
  (testing (str "no symbols: " expected-file)
    (let [expected (string/split-lines (slurp expected-file))
          actual (assemble code)]
      (is (= (count expected) (count actual)))
      (doseq [[e a instr] (map vector
                               expected
                               actual
                               (string/split-lines code))]
        (is (= e a) (str "Instruction: " instr)))))
  (testing (str "with symbols: " expected-file)
    (let [expected (string/split-lines (slurp expected-file))
          actual (assemble (slurp symbol-file))]
      (doseq [[e a] (map vector expected actual)]
        (is (= e a))))))


(deftest test-assemble-rect
  (test-assemble rectL-code "../rect/Rect.asm" "../rect/Rect.hack"))


(deftest test-assemble-max
  (test-assemble maxL-code "../max/Max.asm" "../max/Max.hack"))

(deftest test-assemble-pong
  (test-assemble (slurp "../pong/PongL-no-comment.asm")
                 "../pong/Pong.asm"
                 "../pong/Pong.hack"))


(comment 
  (def test-program-add (slurp "../add/Add.asm"))
  (def pong (slurp "../pong/PongL.asm"))
  (time (def p (string/split-lines pong)))
  (time (def p (parse (string/split-lines pong))))
  (take 10 p)
  (parse "(LOOP)")
  (parse "A=M")
  (parse "@R0")
  (parse "@R2")
  (parse "@223")

  (assemble "0;JMP")


  (write-code (assemble (slurp "../rect/RectL.asm"))
              "../rect/RectL2.hack"
              )


  (assemble test-program-add)
  (assemble (slurp "../max/Max.asm"))
  (write-code (assemble (slurp "../max/MaxL.asm")) "../max/MaxL.mhack")
  )

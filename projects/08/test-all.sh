#!/bin/bash


# program flow

echo "Testing Basic Loop"
PRG_PATH=./ProgramFlow/BasicLoop/BasicLoop
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm"
CPUEmulator.sh "${PRG_PATH}.tst" 

echo "Testing Fibonacci Series"
PRG_PATH=./ProgramFlow/FibonacciSeries/FibonacciSeries
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm"
CPUEmulator.sh "${PRG_PATH}.tst" 



# function calls

echo "Testing Fibonacci Series"
PRG_PATH=./FunctionCalls/FibonacciElement/FibonacciElement
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm"
CPUEmulator.sh "${PRG_PATH}.tst" 

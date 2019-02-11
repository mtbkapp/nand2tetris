#!/bin/bash

echo "Project 07 tests..."

echo "Simple Add"
PRG_PATH="../07/StackArithmetic/SimpleAdd/SimpleAdd"
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh ../07/StackArithmetic/SimpleAdd/SimpleAdd.tst

echo "Stack Test"
PRG_PATH="../07/StackArithmetic/StackTest/StackTest"
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst"

echo "Basic Test"
PRG_PATH="../07/MemoryAccess/BasicTest/BasicTest"
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst"

echo "Pointer Test"
PRG_PATH="../07/MemoryAccess/PointerTest/PointerTest"
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst"

echo "Static Test"
PRG_PATH="../07/MemoryAccess/StaticTest/StaticTest"
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst"


echo "Project 08 tests..."

# program flow

echo "Testing Basic Loop"
PRG_PATH=./ProgramFlow/BasicLoop/BasicLoop
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst" 

echo "Testing Fibonacci Series"
PRG_PATH=./ProgramFlow/FibonacciSeries/FibonacciSeries
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst" 


# function calling

echo "Testing SimpleFunction" 
PRG_PATH=./FunctionCalls/SimpleFunction/SimpleFunction
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs "${PRG_PATH}.vm" --no-prelude --no-sys-init
CPUEmulator.sh "${PRG_PATH}.tst" 

echo "Testing FibonacciElement" 
PRG_PATH=./FunctionCalls/FibonacciElement
rm -f "${PRG_PATH}.asm"
planck ./vm-to-asm-compiler.cljs ${PRG_PATH} 
#CPUEmulator.sh "${PRG_PATH}/FibonacciElement.tst" 

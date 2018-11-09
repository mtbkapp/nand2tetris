// This file is part of www.nand2tetris.org
// and the book "The Elements of Computing Systems"
// by Nisan and Schocken, MIT Press.
// File name: projects/04/Mult.asm

// Multiplies R0 and R1 and stores the result in R2.
// (R0, R1, R2 refer to RAM[0], RAM[1], and RAM[2], respectively.)


// test inputs
// put 3 into R0
//@1
//D=A
//@R0
//M=D

// put 2 into R1
//@0
//D=A
//@R1
//M=D

// optimize by choosing to loop the least amount of times
// Swap R0 and R1 if R0 > R1
// algebra: R0 - R1 > 0

// put R0 into D
@R0
D=M

@R1
D=D-M // R0 - R1

@SWAP
D;JGT

@MULT
0;JMP

(SWAP)
  // put R0 into swap
  @R0
  D=M
  @swap
  M=D

  // put R1 into R0
  @R1
  D=M
  @R0
  M=D

  // put swap into R1
  @swap
  D=M
  @R1
  M=D


//R2 = R2 + R1, R0 times

//accumulate in R2, start at 0
(MULT)
@R2
M=0

// while (R0 > 0)
(loop)
  // check condition
  @R0
  D=M
  @END
  D;JEQ

  // R2 = R2 + 1
  @R1
  D=M
  @R2
  M=M+D


  // decrement R0
  @R0
  M=M-1

  // go back to top of loop
  @loop
  0;JMP


(END)
  @END
  0;JMP




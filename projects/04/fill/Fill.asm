// This file is part of www.nand2tetris.org
// and the book "The Elements of Computing Systems"
// by Nisan and Schocken, MIT Press.
// File name: projects/04/Fill.asm

// Runs an infinite loop that listens to the keyboard input.
// When a key is pressed (any key), the program blackens the screen,
// i.e. writes "black" in every pixel;
// the screen should remain fully black as long as the key is pressed.
// When no key is pressed, the program clears the screen, i.e. writes
// "white" in every pixel;
// the screen should remain fully clear as long as no key is pressed.

// Put your code here.

// function calling routine:
//    1 put return address in @return
//    2 jump to function
//    3 function runs
//    4 function must jump to @return

// make white and black constants
// black 0xffff
@32767 // A is only 15 bits, no A is 16 bits but an A instruction can only hold 15 bits!
D=A
D=D+A // 0x7fff + 0x7fff = 0xfffe
D=D+1 // + 1 to get to 0xffff
@black
M=D   // store @black

@0
D=A
@white
M=D

// start loop to get keyboard input
(LOOP)
  // set to white
  @white
  D=M
  @color
  M=D

  // if not @KBD 0 set to black
  @KBD
  D=M

  @isBlack
  D;JNE
  @startFill
  0;JMP

  (isBlack)
    // put @black into @color
    @black
    D=M
    @color
    M=D

  (startFill)
  // fill screen with @color, 256 rows of 32 words each 16 bits / word
  // 0x2000 words = 8192 words = 256 * 32 words

  //set i = 0
  @0
  D=A
  @i
  M=D

  (fill)
    A=A // marker
    //write color to SCREEN + i

    // put screen + i into R0
    @SCREEN
    D=A
    @i
    D=D+M
    @R0
    M=D

    // put color into D
    @color
    D=M

    // write D to address in @R0
    @R0
    A=M
    M=D

    //increment i
    @i
    M=M+1

    // i < 8192?
    // 0 < 8192 - i
    // D = 8192 - i
    @8192
    D=A
    @i
    D=D-M

    // if D > 0 go to @fill
    @fill
    D;JGT


  (ENDLOOP)
  @LOOP
  0;JMP


//Despertando

void setup (){ 
  size (400,150); 
}
void draw() {

  int s = second();  // 
  float r= random(10,40);
  background(s*3,s*10,s*11); 
//boca    
  //line (180, 200, 200+s, 200);   

//ojo izquierdo
  ellipse (100, 75, 75, s); 
  ellipse (100, 75, 10+s, s); // pupila 

//ojo derecho
  ellipse (300, 75, 75, s); 
  ellipse (300, 75, 10+s, s); // pupila 
 
  stroke (0);  
  strokeWeight (5); 
  fill (255, s*10, 20*s); 
  
}

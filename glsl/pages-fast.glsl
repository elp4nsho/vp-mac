int vpLevelBase(int s,int level) {
 int base=vpPageImage[s].z; ivec2 dims=vpPageImage[s].xy; int components=vpPageComponents[s]&255;
 for(int k=0;k<level;k++) { base+=(dims.x*dims.y*components+3)/4;dims=max(dims/2,ivec2(1)); }
 return base;
}
vec4 vpRaw(int s,ivec2 at,ivec2 size,int base,bool wrap) {
 if(wrap)at=ivec2(vpWrap(at.x,size.x,vpPageFilter[s].z),vpWrap(at.y,size.y,vpPageFilter[s].w));
 if(any(lessThan(at,ivec2(0)))||any(greaterThanEqual(at,size)))return vpPageBorder[s];
 int index=at.y*size.x+at.x; vec4 c;
 if((vpPageComponents[s]&255)==1){c=vec4(texelFetch(uVpPages,base+(index>>2))[index&3],0.0,0.0,1.0);}else c=texelFetch(uVpPages,base+index);
 if((vpPageComponents[s]&256)!=0)c.rgb=mix(c.rgb/12.92,pow((c.rgb+0.055)/1.055,vec3(2.4)),greaterThan(c.rgb,vec3(0.04045)));
 return c;
}
vec4 vpPagePixel(int s,ivec2 at,int level,bool wrap) {
 return vpSwizzled(s,vpRaw(s,at,textureSize(s,level),vpLevelBase(s,level),wrap));
}
vec4 texelFetch(int s,ivec2 at,int level) {return vpPagePixel(s,at,clamp(level,0,vpPageImage[s].w-1),false);}
vec4 vpPageFiltered(int s,vec2 uv,int low,int high,float t,bool linear) {
 int levels=t>0.0?2:1; vec4 acc=vec4(0.0);
 for(int li=0;li<levels;li++) {
  int level=li==0?low:high; float wl=levels==1?1.0:(li==0?1.0-t:t);
  ivec2 size=textureSize(s,level); int base=vpLevelBase(s,level);
  vec2 p=uv*vec2(size); vec2 f=vec2(0.0); ivec2 at;
  if(linear){p-=0.5; f=fract(p);} at=ivec2(floor(p));
  int taps=linear?4:1;
  for(int k=0;k<taps;k++) {
   ivec2 o=ivec2(k&1,k>>1);
   float w=linear?(o.x==1?f.x:1.0-f.x)*(o.y==1?f.y:1.0-f.y):1.0;
   acc+=(wl*w)*vpRaw(s,at+o,size,base,true);
  }
 }
 return acc;
}
vec4 vpPageLevel(int s,vec2 uv,int level,bool linear) {return vpSwizzled(s,vpPageFiltered(s,uv,level,level,0.0,linear));}
vec4 vpPageSample(int s,vec2 uv,float lod,bool bias) {
 lod=clamp(lod+(bias?vpPageLod[s].z:0.0),vpPageLod[s].x,vpPageLod[s].y);int filtering=vpPageFilter[s].x;
 int low=0,high=0; float t=0.0; bool linear;
 if(lod<=0.0) linear=vpPageFilter[s].y==9729;
 else if(filtering==9728||filtering==9729) linear=filtering==9729;
 else {
  lod=clamp(lod,0.0,float(vpPageImage[s].w-1)); linear=filtering==9985||filtering==9987;
  if(filtering==9984||filtering==9985) {low=int(floor(lod+0.5));high=low;}
  else {low=int(floor(lod));high=min(low+1,vpPageImage[s].w-1);t=fract(lod);}
 }
 return vpSwizzled(s,vpPageFiltered(s,uv,low,high,t,linear));
}

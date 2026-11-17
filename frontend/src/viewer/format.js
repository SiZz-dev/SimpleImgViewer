export const clamp=(n,min,max)=>Math.max(min,Math.min(max,n));
export const bytes=n=>n>1048576?(n/1048576).toFixed(1)+' MB':Math.round(n/1024)+' KB';

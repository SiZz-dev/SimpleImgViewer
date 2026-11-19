export const clamp=(n,min,max)=>Math.max(min,Math.min(max,n));
// "100 MB", "1.5 MB", "25 KB" — no trailing ".0" (H-g).
export const bytes=n=>n>=1048576?Number((n/1048576).toFixed(1))+' MB':Math.round(n/1024)+' KB';

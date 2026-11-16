export class ApiError extends Error {
  constructor(status,code){super(code);this.status=status;this.code=code;}
}
export function requireThat(condition,status=400,code='INVALID_REQUEST'){if(!condition)throw new ApiError(status,code);}
export const uuidPattern=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
export const integer=(n,min,max)=>Number.isSafeInteger(n)&&n>=min&&n<=max;
export const finite=(n,min,max)=>Number.isFinite(n)&&n>=min&&n<=max;

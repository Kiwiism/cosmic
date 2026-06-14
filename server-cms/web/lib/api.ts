const API=process.env.NEXT_PUBLIC_SERVER_CMS_API_URL??"http://localhost:8082";
export async function api<T>(path:string,init?:RequestInit):Promise<T>{
  const response=await fetch(`${API}${path}`,{...init,credentials:"include",headers:{"Content-Type":"application/json",...init?.headers}});
  if(!response.ok){const body=await response.json().catch(()=>null);throw new Error(body?.detail??body?.message??`${response.status} ${response.statusText}`)}
  if(response.status===204)return undefined as T;
  return response.json() as Promise<T>;
}

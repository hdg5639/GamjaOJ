// Compact display codes; routes, submissions and repository mappings retain their original IDs.
export function shortProblemId(version='') {
 if(version.length<=20)return version;
 let hash=0xcbf29ce484222325n;
 for(const byte of new TextEncoder().encode(version))hash=BigInt.asUintN(64,(hash^BigInt(byte))*0x100000001b3n);
 return '#'+hash.toString(36).toUpperCase().padStart(13,'0');
}
export default function ProblemId({version,className='version'}) {
 return <span className={className} title={version}>{shortProblemId(version)}</span>;
}

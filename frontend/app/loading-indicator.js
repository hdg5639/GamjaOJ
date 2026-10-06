export default function LoadingIndicator({children,className=''}){
  return <p className={`brand-loading ${className}`.trim()} role="status"><img src="/gamjaoj-loader.svg" width="38" height="38" alt="" aria-hidden="true"/><span>{children}</span></p>;
}

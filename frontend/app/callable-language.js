export function callableLanguage(bundle, language) {
  return bundle?.languages?.[language] || (language === 'JAVA' ? bundle : null);
}

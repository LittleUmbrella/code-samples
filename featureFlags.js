const cookieCutter = require('cookie-cutter');
const ServerProp = require('@rei/server-prop');

module.exports.isFeatureOn = featureName =>
  new ServerProp('app-props').get(featureName) === 'true' || cookieCutter.get('Speedy') === 'true';


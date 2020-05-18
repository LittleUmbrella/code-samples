/**
 * Welcome to your friendly merger burger shop.  We supply the buns, you supply
 * the beef.  We don't know where it's made; we don't know how it's made; we don't
 * know what it's made of.  And we don't want to know... but we will put it between
 * two buns (the read bun and the write bun).
 *
 * The focus here is on reading the file passed in from both a defaults dir and an
 * overrides dir (all of this is the bottom bun).  The contents of same are sent
 * to the beefCb function/delegate.  We then write the results of the delegate call
 * to the output dir (the top bun).
 */
const serveMergerBurger = async ({
  defaultsDir,
  overridesDir,
  outputDir,
  fileName,
  beefCb, /* Cb = Char-Broiled, NOT callback */
  /* below params are exposed for simpler unit testing */
  fsPromises = require('fs').promises,
  path = require('path'),
  log = (message) => console.log(message),
}) => {
  const pathToOverridesFile = path.resolve(overridesDir, fileName);
  const pathToDefaultsFile = path.resolve(defaultsDir, fileName);
  const pathToOutputFile = path.resolve(outputDir, fileName);

  let defaultsFileContent = '';
  let overridesFileContent = '';

  /* Let both reads run in parallel (bottom burger bun) */
  await Promise.all([
    fsPromises.readFile(pathToDefaultsFile, 'utf8').then((r) => { defaultsFileContent = r; }),
    fsPromises.readFile(pathToOverridesFile, 'utf8').then((r) => { overridesFileContent = r; }),
  ]);

  /* Get the beef on the bottom bun */
  const mergedOutput = beefCb({ defaultsFileContent, overridesFileContent });

  log(`Merging ${fileName}:
    DEFAULT ${fileName} 
    ${defaultsFileContent}

    OVERRIDE ${fileName}
     ${overridesFileContent}

    MERGED OUTPUT ${fileName}
     ${mergedOutput}
   `);

  /* Top burger bun.  Your merger burger is served.  Enjoy. */
  fsPromises.writeFile(pathToOutputFile, mergedOutput);
};

module.exports = serveMergerBurger;

# java-project-template

Template for my Java projects, already configured for maven publishing (needs CENTRAL_USERNAME, CENTRAL_PASSWORD, GPG_PRIVATE_KEY, GPG_PASSPHRASE to actually publish).

Default JDK is Java8.

If you need to use a newer JDK, edit maven.compiler.release in pom.xml, and fix java: [8, 11, 17, 21, 25] in ci.yml accordingly.

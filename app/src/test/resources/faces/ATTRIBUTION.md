# Test photographs

Real photographs used only by the unit tests of the face pipeline (`app/src/test`), never shipped in the app. Every one is marked **public domain** on Wikimedia Commons (works of the United States government: NASA, the Air Force, the Senate). They are downscaled copies served by Commons (960 px wide) and are kept byte for byte as downloaded.

The licence is the one Commons shows on each page on the day they were fetched (2026-10-26); it is not legal advice. If a page is later corrected, remove the file.

| File | Commons page | Licence shown | Author or credit shown |
|---|---|---|---|
| `collins_1.jpg` | <https://commons.wikimedia.org/wiki/File:Commander_Eileen_Collins_-_GPN-2000-001177.jpg> | Public domain | Robert Markowitz |
| `collins_2.jpg` | <https://commons.wikimedia.org/wiki/File:Eileen_Collins_(S93-E-5033,_1999-07-24).jpg> | Public domain | This image or video was catalogued by Lyndon B. Johnson Space Center of the United States  |
| `collins_3.jpg` | <https://commons.wikimedia.org/wiki/File:Eileen_Collins_at_Edwards_AFB.jpg> | Public domain | United States Air Force |
| `ride_1.jpg` | <https://commons.wikimedia.org/wiki/File:Sally_Ride_(1984).jpg> | Public domain | NASA; retouched by Coffeeandcrumbs |
| `ride_2.jpg` | <https://commons.wikimedia.org/wiki/File:S83-35763_(cropped).jpg> | Public domain | NASA |
| `ride_3.jpg` | <https://commons.wikimedia.org/wiki/File:Sally_K._Ride_San_Diego_Air_and_Space_Museum_Archive.jpg> | Public domain | SDASM Archives |
| `glenn_1.jpg` | <https://commons.wikimedia.org/wiki/File:John_Glenn_in_1960.jpg> | Public domain | NASA |
| `glenn_2.jpg` | <https://commons.wikimedia.org/wiki/File:Sen._John_Glenn.jpg> | Public domain | United States Senate |
| `glenn_3.jpg` | <https://commons.wikimedia.org/wiki/File:John_Glenn_Mercury_(small).jpg> | Public domain | not stated |
| `crew_sts93.jpg` | <https://commons.wikimedia.org/wiki/File:STS-93_crew.jpg> | Public domain | NASA |
| `press_four.jpg` | <https://commons.wikimedia.org/wiki/File:Space_Shuttle_Discovery_launch_2.jpg> | Public domain | NASA/KSC |

People: the single-person photos show Eileen Collins (`collins_*`), Sally Ride (`ride_*`) and John Glenn (`glenn_*`), as named on the Commons pages. `crew_sts93.jpg` is the STS-93 crew (five faces, Collins among them) and `press_four.jpg` a press appearance with four people (Collins among them). The tests never use these names: they only check that photos of the same person group together and that different people do not.

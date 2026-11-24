-- Reference + catalog seed data (users/partners are created by DemoDataSeeder at startup)
INSERT INTO cities(id,name,state,active,center_lat,center_lng) VALUES (1,'Bengaluru','Karnataka',TRUE,12.9716,77.5946);
INSERT INTO zones(id,city_id,name,center_lat,center_lng,radius_km,polygon_coords,active) VALUES
 (1,1,'Koramangala',12.9352,77.6245,6,NULL,TRUE),
 (2,1,'Indiranagar',12.9784,77.6408,5,NULL,TRUE),
 (3,1,'HSR Layout',12.9116,77.6389,5,NULL,TRUE);

INSERT INTO categories(id,parent_id,name,slug,sort_order) VALUES
 (1,NULL,'Appliance Care','appliance-care',1),
 (2,1,'AC Service & Repair','ac-service-repair',1),
 (3,1,'Washing Machine Repair','washing-machine-repair',2),
 (4,NULL,'Home Cleaning','home-cleaning',2),
 (5,4,'Bathroom & Kitchen Cleaning','bathroom-kitchen-cleaning',1),
 (6,4,'Full Home Cleaning','full-home-cleaning',2),
 (7,NULL,'Plumber, Electrician & Carpenter','plumber-electrician',3),
 (8,7,'Plumbing','plumbing',1),
 (9,7,'Electrical','electrical',2);

INSERT INTO services(id,category_id,name,slug,description) VALUES
 (1,2,'AC Service & Repair','ac-service-repair-svc','Foam-jet service, gas refill, installation by trained technicians'),
 (2,3,'Washing Machine Repair','washing-machine-repair-svc','Top-load and front-load diagnosis and repair'),
 (3,5,'Bathroom & Kitchen Deep Clean','bathroom-kitchen-deep-clean','Machine-assisted deep cleaning'),
 (4,6,'Full Home Deep Cleaning','full-home-deep-cleaning','Top-to-bottom home cleaning'),
 (5,8,'Plumbing Repairs','plumbing-repairs','Taps, basins, pipes and fittings'),
 (6,9,'Electrical Repairs','electrical-repairs','Switches, sockets, fans and wiring');

INSERT INTO service_packages(id,service_id,name,description,base_price,duration_minutes,max_quantity,instant_eligible,attributes_json) VALUES
 (1,1,'AC Service (Foam-jet)','Per-unit deep service with foam-jet wash',599,60,6,FALSE,'{"warrantyDays":30,"includes":["indoor unit wash","outdoor unit wash","gas pressure check"]}'),
 (2,1,'AC Gas Refill & Leak Fix','Gas top-up with leak inspection',2499,90,3,FALSE,'{"warrantyDays":30,"includes":["gas refill","leak check"]}'),
 (3,1,'AC Installation','Split/window AC installation',1499,90,4,FALSE,'{"warrantyDays":30}'),
 (4,2,'Washing Machine Inspection & Repair','Diagnosis and minor repair (parts extra)',299,45,2,TRUE,'{"warrantyDays":30}'),
 (5,3,'Bathroom Deep Clean','Per-bathroom intensive cleaning',449,60,5,FALSE,'{"warrantyDays":7}'),
 (6,3,'Kitchen Deep Clean','Cabinets, slab, tiles and appliances',1099,120,1,FALSE,'{"warrantyDays":7}'),
 (7,4,'Full Home Deep Clean','Complete home deep clean',3499,240,1,FALSE,'{"warrantyDays":7}'),
 (8,5,'Tap / Basin / Pipe Repair','Quick fixes (spare parts extra)',149,30,5,TRUE,'{"warrantyDays":30}'),
 (9,6,'Switch / Socket / Fan Repair','Quick electrical fixes (parts extra)',99,30,10,TRUE,'{"warrantyDays":30}');

INSERT INTO addon_questions(id,package_id,question_key,question_text,type,required,sort_order) VALUES
 (1,1,'ac_type','What type of AC do you have?','SINGLE_SELECT',TRUE,1),
 (2,1,'addons','Add-ons for your AC service','MULTI_SELECT',FALSE,2),
 (3,2,'gas_type','Which refrigerant does your AC use?','SINGLE_SELECT',TRUE,1),
 (4,2,'leak_check','Need leak repair?','MULTI_SELECT',FALSE,2),
 (5,5,'bathroom_size','Bathroom size','SINGLE_SELECT',TRUE,1),
 (6,5,'extras','Extras','MULTI_SELECT',FALSE,2),
 (7,6,'appliances','Appliance add-ons','MULTI_SELECT',FALSE,1),
 (8,7,'bhk','Home size','SINGLE_SELECT',TRUE,1),
 (9,4,'machine_type','Machine type','SINGLE_SELECT',TRUE,1),
 (10,8,'issue','What needs fixing?','SINGLE_SELECT',TRUE,1);

INSERT INTO addon_options(question_id,option_key,label,price_delta,duration_delta_minutes) VALUES
 (1,'SPLIT','Split AC',0,0),(1,'WINDOW','Window AC',-100,-10),
 (2,'ANTI_RUST','Anti-rust coating',199,15),(2,'DEEP_JET','Deep jet cleaning',249,20),
 (3,'R32','R32',0,0),(3,'R410A','R410A',500,0),(3,'R22','R22',300,0),
 (4,'LEAK_FIX','Leak repair',799,30),
 (5,'STANDARD','Standard (up to 50 sq ft)',0,0),(5,'LARGE','Large (50+ sq ft)',150,20),
 (6,'TILE_STAIN','Tile stain removal',199,15),(6,'CEILING','Ceiling cleaning',99,10),
 (7,'CHIMNEY_CLEAN','Chimney cleaning',399,30),(7,'FRIDGE_CLEAN','Fridge cleaning',299,20),
 (8,'ONE_BHK','1 BHK',0,0),(8,'TWO_BHK','2 BHK',1500,60),(8,'THREE_BHK','3 BHK',3000,120),
 (9,'TOP_LOAD','Top load',0,0),(9,'FRONT_LOAD','Front load',100,0),
 (10,'LEAKING_TAP','Leaking tap',0,0),(10,'BLOCKED_BASIN','Blocked basin',50,0),(10,'PIPE_LEAK','Pipe leakage',150,15);

INSERT INTO rate_cards(service_id,item_name,price,unit,description) VALUES
 (1,'AC capacitor',350,'piece','Replacement capacitor incl. fitting'),
 (1,'AC PCB repair',2200,'piece','Control board repair'),
 (1,'Refrigerant gas top-up',900,'per kg','R32 / R410A'),
 (2,'Drain pump',650,'piece','Replacement drain pump'),
 (2,'Door lock',450,'piece','Front-load door lock'),
 (5,'Tap fitting',250,'piece','Standard CP tap'),
 (5,'Flush valve',400,'piece','Concealed flush valve'),
 (6,'Switch',60,'piece','Modular switch'),
 (6,'Ceiling fan capacitor',120,'piece','Capacitor replacement'),
 (6,'MCB',220,'piece','Single pole MCB');

INSERT INTO commission_tiers(name,min_jobs,commission_percent) VALUES
 ('Standard',0,25.00),('Silver',50,22.00),('Gold',200,20.00),('Platinum',500,18.00);

INSERT INTO surge_rules(name,zone_id,start_hour,end_hour,day_of_week,multiplier) VALUES
 ('Evening peak',NULL,18,21,NULL,1.20),
 ('Weekend morning peak',NULL,10,14,6,1.15),
 ('Weekend morning peak (Sun)',NULL,10,14,7,1.15);

INSERT INTO coupons(code,discount_type,discount_value,max_discount,min_order,valid_from,valid_to,usage_limit,per_user_limit) VALUES
 ('WELCOME100','FLAT',100,NULL,499,now() - interval '1 day',now() + interval '365 days',100000,1),
 ('SAVE15','PERCENT',15,200,699,now() - interval '1 day',now() + interval '365 days',100000,3);

INSERT INTO membership_plans(name,price,duration_days,discount_percent,zero_convenience_fee) VALUES
 ('UC Plus Annual',299,365,10.00,TRUE),
 ('UC Plus Quarterly',99,90,10.00,TRUE);

SELECT setval(pg_get_serial_sequence('cities','id'),(SELECT max(id) FROM cities));
SELECT setval(pg_get_serial_sequence('zones','id'),(SELECT max(id) FROM zones));
SELECT setval(pg_get_serial_sequence('categories','id'),(SELECT max(id) FROM categories));
SELECT setval(pg_get_serial_sequence('services','id'),(SELECT max(id) FROM services));
SELECT setval(pg_get_serial_sequence('service_packages','id'),(SELECT max(id) FROM service_packages));
SELECT setval(pg_get_serial_sequence('addon_questions','id'),(SELECT max(id) FROM addon_questions));
